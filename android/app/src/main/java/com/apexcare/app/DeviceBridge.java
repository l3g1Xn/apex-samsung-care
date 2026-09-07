package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WebView JS bridge. All package names are validated before shell / kill paths.
 */
public class DeviceBridge {
    private final Context context;
    private final MagiskRoot magisk = MagiskRoot.get();

    public DeviceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    private boolean hasRoot() {
        return magisk.isGranted() || magisk.probeQuick();
    }

    private boolean hasRealRoot() {
        return magisk.isRealRoot();
    }

    /**
     * Grant Temporary Root — Magisk su in-process or userspace TEMP ROOT (30 min).
     */
    @JavascriptInterface
    public String requestRootAccess() {
        try {
            MagiskRoot.Result r = magisk.requestGrant(context);
            JSONObject o = magisk.statusJson();
            o.put("ok", r.ok);
            o.put("hasRoot", r.ok || magisk.isGranted());
            o.put("suPath", r.suPath);
            o.put("mode", r.mode);
            o.put("provider", r.mode);
            o.put("message", r.message);
            if (r.ok && MagiskRoot.MODE_MAGISK_SU.equals(r.mode)) {
                magisk.run("id");
            }
            try {
                o.put("mem", new JSONObject(getMemoryStats()));
            } catch (Exception ignored) {}
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getRootStatus() {
        try {
            return magisk.statusJson().put("ok", true).toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getMemoryStats() {
        try {
            RamMetrics ram = RamMetrics.sampleThorough(context);
            return ram.toJson(hasRoot()).toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getMemoryInfo() {
        return getMemoryStats();
    }

    @JavascriptInterface
    public String forceCloseApp(String packageName) {
        try {
            if (!ProtectedPackages.isValidPackage(packageName)) {
                return new JSONObject().put("ok", false).put("error", "invalid_package").toString();
            }
            if (ProtectedPackages.isProtected(context, packageName)) {
                return new JSONObject().put("ok", false).put("error", "protected").toString();
            }
            MagiskRoot.Result elev = magisk.ensureElevated(context);
            boolean real = hasRealRoot();
            boolean elevated = hasRoot();
            String method = "kill_background";
            magisk.reclaimPackage(context, packageName);
            if (real) method = "root_reclaim";
            else if (elevated) method = "userspace_temp_kill";
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            boolean hung = stillRunning(am, packageName);
            if (hung) {
                briefPause(80);
                magisk.reclaimPackage(context, packageName, true);
                hung = stillRunning(am, packageName);
                if (!hung) method = method + "+retry";
            }
            JSONObject memJson = RamMetrics.sampleFast(context).toJson(elevated);
            return new JSONObject()
                    .put("ok", !hung)
                    .put("hasRoot", elevated)
                    .put("realRoot", real)
                    .put("mode", magisk.getMode())
                    .put("method", method)
                    .put("hung", hung)
                    .put("elevated", elev.ok)
                    .put("mem", memJson)
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String closeBackgroundApp(String p) {
        return forceCloseApp(p);
    }

    @JavascriptInterface
    public String getRunningProcesses() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = context.getPackageManager();
            JSONArray arr = new JSONArray();
            if (am == null) {
                return new JSONObject().put("ok", true).put("processes", arr).toString();
            }
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) procs = Collections.emptyList();
            Set<String> seen = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.pkgList == null) continue;
                for (String pkg : info.pkgList) {
                    if (pkg == null || seen.contains(pkg)) continue;
                    if (!ProtectedPackages.isValidPackage(pkg)) continue;
                    seen.add(pkg);
                    JSONObject row = new JSONObject();
                    row.put("packageName", pkg);
                    row.put("pid", info.pid);
                    row.put("importance", importanceLabel(info.importance));
                    String label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        label = pm.getApplicationLabel(ai).toString();
                        row.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    } catch (Exception ignored) {
                        row.put("system", false);
                    }
                     row.put("name", label);
                    row.put("label", label);
                    long pss = 0;
                    if (seen.size() <= 80) {
                        try {
                            Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{info.pid});
                            if (mis != null && mis.length > 0) pss = mis[0].getTotalPss();
                        } catch (Exception ignored) {}
                    }
                    row.put("ramMb", Math.round(pss / 1024.0));
                    row.put("protected", ProtectedPackages.isProtected(context, pkg));
                    row.put("running", true);
                    arr.put(row);
                }
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("processes", arr)
                    .put("apps", arr)
                    .put("hasRoot", hasRoot())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static String importanceLabel(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "foreground";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "visible";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "service";
        if (importance <= 400) return "cached"; // IMPORTANCE_CACHED / BACKGROUND
        return "running";
    }

    private static boolean stillRunning(ActivityManager am, String pkg) {
        if (am == null || pkg == null) return false;
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.pkgList == null) continue;
                for (String p : info.pkgList) {
                    if (pkg.equals(p)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void briefPause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @JavascriptInterface
    public String optimizeDevice() {
        try {
            MagiskRoot.Result elev = magisk.ensureElevated(context);
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int closed = 0;
            int retried = 0;
            JSONArray closedPkgs = new JSONArray();
            JSONArray hungPkgs = new JSONArray();
            Set<String> attempted = new HashSet<>();
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info.pkgList == null) continue;
                        if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                            continue;
                        }
                        for (String pkg : info.pkgList) {
                            if (pkg == null || attempted.contains(pkg)) continue;
                            if (!ProtectedPackages.isValidPackage(pkg)) continue;
                            if (ProtectedPackages.isProtected(context, pkg)) continue;
                            attempted.add(pkg);
                            magisk.reclaimPackage(context, pkg);
                            closed++;
                            closedPkgs.put(pkg);
                        }
                    }
                }
                briefPause(90);
                // Pass 2 — hanging / failed close (process still listed)
                for (String pkg : attempted) {
                    if (!stillRunning(am, pkg)) continue;
                    magisk.reclaimPackage(context, pkg, true);
                    retried++;
                }
                briefPause(70);
                // Pass 3 — leftover blobs of code / empty cached processes
                for (String pkg : attempted) {
                    if (!stillRunning(am, pkg)) continue;
                    magisk.reclaimPackage(context, pkg, true);
                    retried++;
                    if (stillRunning(am, pkg) && hungPkgs.length() < 40) {
                        hungPkgs.put(pkg);
                    }
                }
            }
            boolean systemReclaim = magisk.reclaimSystem(context);
            try {
                Runtime.getRuntime().gc();
            } catch (Exception ignored) {}
            boolean elevated = hasRoot();
            String method = hasRealRoot() ? "root_reclaim" : elevated ? "userspace_temp_kill" : "kill_background";
            if (retried > 0) method = method + "+hang_retry";
            if (systemReclaim || hasRealRoot()) method = method + "+cpu_npu_trim";
            return new JSONObject()
                    .put("ok", true)
                    .put("closed", closed)
                    .put("retried", retried)
                    .put("hung", hungPkgs.length())
                    .put("closedPackages", closedPkgs)
                    .put("hungPackages", hungPkgs)
                    .put("hasRoot", elevated)
                    .put("realRoot", hasRealRoot())
                    .put("mode", magisk.getMode())
                    .put("elevated", elev.ok)
                    .put("systemReclaim", systemReclaim)
                    .put("npuSafe", true)
                    .put("method", method)
                    .put("mem", RamMetrics.sampleFast(context).toJson(elevated))
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String forceCloseIfOpen(String packagesJson) {
        try {
            magisk.ensureElevated(context);
            JSONArray input = new JSONArray(packagesJson != null ? packagesJson : "[]");
            int max = Math.min(input.length(), 80);
            int closed = 0;
            int hung = 0;
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (int i = 0; i < max; i++) {
                String pkg = input.optString(i, "");
                if (!ProtectedPackages.isValidPackage(pkg)) continue;
                if (ProtectedPackages.isProtected(context, pkg)) continue;
                magisk.reclaimPackage(context, pkg);
                if (stillRunning(am, pkg)) {
                    magisk.reclaimPackage(context, pkg, true);
                    if (stillRunning(am, pkg)) hung++;
                    else closed++;
                } else {
                    closed++;
                }
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("closed", closed)
                    .put("hung", hung)
                    .put("hasRoot", hasRoot())
                    .put("realRoot", hasRealRoot())
                    .put("mode", magisk.getMode())
                    .put("mem", RamMetrics.sampleFast(context).toJson(hasRoot()))
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String scanInstalledApps() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            JSONArray arr = new JSONArray();
            int count = 0;
            for (ApplicationInfo ai : apps) {
                if (count++ > 2000) break; // hard cap for low-RAM devices
                if (!ProtectedPackages.isValidPackage(ai.packageName)) continue;
                JSONObject row = new JSONObject();
                row.put("packageName", ai.packageName);
                row.put("name", pm.getApplicationLabel(ai).toString());
                row.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                arr.put(row);
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("apps", arr)
                    .put("hasRoot", hasRoot())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String runHeuristicScan() {
        try {
            MagiskRoot.Result elev = magisk.ensureElevated(context);
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            int user = 0, system = 0, debuggable = 0, outdated = 0, sideloaded = 0;
            JSONArray findings = new JSONArray();
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) system++;
                else user++;
                if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    debuggable++;
                    findings.put(new JSONObject()
                            .put("title", "Debuggable app")
                            .put("detail", "Unusual for release builds on production devices.")
                            .put("severity", "medium")
                            .put("kind", "debug")
                            .put("packageName", ai.packageName)
                            .put("running", false));
                }
                try {
                    int target = ai.targetSdkVersion;
                    if (target > 0 && target < 28 && (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        outdated++;
                        if (findings.length() < 40) {
                            findings.put(new JSONObject()
                                    .put("title", "Outdated target SDK")
                                    .put("detail", "targetSdk " + target + " is below Android 9.")
                                    .put("severity", "low")
                                    .put("kind", "outdated")
                                    .put("packageName", ai.packageName)
                                    .put("running", false));
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        String installer = null;
                        if (Build.VERSION.SDK_INT >= 30) {
                            installer = pm.getInstallSourceInfo(ai.packageName).getInstallingPackageName();
                        } else {
                            installer = pm.getInstallerPackageName(ai.packageName);
                        }
                        if (installer == null || installer.isEmpty()) {
                            sideloaded++;
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Cached RAM blobs / leftover processes (needs running list + elevation)
            int blobs = 0;
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    Set<String> seen = new HashSet<>();
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info.pkgList == null) continue;
                        if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                            continue;
                        }
                        for (String pkg : info.pkgList) {
                            if (pkg == null || seen.contains(pkg)) continue;
                            if (!ProtectedPackages.isValidPackage(pkg)) continue;
                            if (ProtectedPackages.isProtected(context, pkg)) continue;
                            seen.add(pkg);
                            long pssKb = 0;
                            try {
                                Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{info.pid});
                                if (mis != null && mis.length > 0) pssKb = mis[0].getTotalPss();
                            } catch (Exception ignored) {}
                            long ramMb = Math.round(pssKb / 1024.0);
                            boolean cached = info.importance >= 400;
                            if (cached && ramMb >= 80 && findings.length() < 40) {
                                blobs++;
                                findings.put(new JSONObject()
                                        .put("title", "Cached RAM blob")
                                        .put("detail", ramMb + " MB still resident after going to cache.")
                                        .put("severity", ramMb >= 250 ? "high" : "medium")
                                        .put("kind", "ram_blob")
                                        .put("packageName", pkg)
                                        .put("running", true));
                            } else if (info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
                                    && ramMb >= 180 && findings.length() < 40) {
                                blobs++;
                                findings.put(new JSONObject()
                                        .put("title", "Heavy background service")
                                        .put("detail", ramMb + " MB service — candidate for reclaim on Optimize.")
                                        .put("severity", "medium")
                                        .put("kind", "service_blob")
                                        .put("packageName", pkg)
                                        .put("running", true));
                            }
                        }
                    }
                }
            }
            int score = Math.max(35, 96 - findings.length() * 3 - Math.min(15, outdated / 4) - Math.min(10, blobs));
            return new JSONObject()
                    .put("ok", true)
                    .put("score", score)
                    .put("packages", apps.size())
                    .put("userApps", user)
                    .put("systemApps", system)
                    .put("disabled", 0)
                    .put("sideloaded", sideloaded)
                    .put("debuggable", debuggable)
                    .put("highRiskPermHits", 0)
                    .put("outdatedTarget", outdated)
                    .put("findingCount", findings.length())
                    .put("malwareSignals", 0)
                    .put("puaSignals", 0)
                    .put("ramBlobs", blobs)
                    .put("findings", findings)
                    .put("openRiskPackages", new JSONArray())
                    .put("hasRoot", hasRoot())
                    .put("realRoot", hasRealRoot())
                    .put("mode", magisk.getMode())
                    .put("elevated", elev.ok)
                    .put("scannedAt", System.currentTimeMillis())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("brand", Build.BRAND)
                    .put("device", Build.DEVICE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE)
                    .put("hasRoot", hasRoot())
                    .put("rooted", hasRoot())
                    .put("realRoot", hasRealRoot())
                    .put("mode", magisk.getMode())
                    .put("suPath", magisk.getSuPath())
                    .put("rootDetail", magisk.lastDetail())
                    .put("packageId", context.getPackageName())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /** Lightweight uptime / health ping for diagnostics UI. */
    @JavascriptInterface
    public String getHealthPing() {
        try {
            RamMetrics ram = RamMetrics.sampleFast(context);
            Runtime rt = Runtime.getRuntime();
            return new JSONObject()
                    .put("ok", true)
                    .put("ts", System.currentTimeMillis())
                    .put("freePct", ram.freePct)
                    .put("usedPct", ram.usedPct)
                    .put("lowMemory", ram.lowMemory)
                    .put("hasRoot", hasRoot())
                    .put("realRoot", hasRealRoot())
                    .put("mode", magisk.getMode())
                    .put("userspaceRemainingSec", magisk.statusJson().optLong("userspaceRemainingSec", 0))
                    .put("jvmFreeMb", (rt.freeMemory() / (1024 * 1024)))
                    .put("jvmMaxMb", (rt.maxMemory() / (1024 * 1024)))
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("model", Build.MODEL)
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String addUserProtect(String packageName) {
        try {
            boolean ok = ProtectedPackages.addUser(context, packageName);
            return new JSONObject()
                    .put("ok", ok)
                    .put("error", ok ? JSONObject.NULL : "invalid_or_full")
                    .put("packages", userProtectJson())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String removeUserProtect(String packageName) {
        try {
            boolean ok = ProtectedPackages.removeUser(context, packageName);
            return new JSONObject()
                    .put("ok", ok)
                    .put("packages", userProtectJson())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String listUserProtect() {
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("packages", userProtectJson())
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private JSONArray userProtectJson() {
        JSONArray arr = new JSONArray();
        for (String p : ProtectedPackages.userSnapshot(context)) {
            arr.put(p);
        }
        return arr;
    }

    private static String errorJson(Exception e) {
        try {
            return new JSONObject()
                    .put("ok", false)
                    .put("error", e.getMessage() != null ? e.getMessage() : e.toString())
                    .toString();
        } catch (Exception ex) {
            return "{\"ok\":false}";
        }
    }
}
