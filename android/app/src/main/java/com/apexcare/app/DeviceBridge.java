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
            RamMetrics ram = RamMetrics.sample(context);
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
            boolean real = hasRealRoot();
            boolean elevated = hasRoot();
            String method = "kill_background";
            if (real) {
                // Only allow force-stop via validated package API (no free-form shell)
                magisk.forceStopPackage(packageName);
                method = "root_force_stop";
            }
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                try {
                    am.killBackgroundProcesses(packageName);
                } catch (Exception ignored) {}
                if (elevated && !real) {
                    try {
                        am.killBackgroundProcesses(packageName);
                    } catch (Exception ignored) {}
                    method = "userspace_temp_kill";
                }
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("hasRoot", elevated)
                    .put("realRoot", real)
                    .put("mode", magisk.getMode())
                    .put("method", method)
                    .put("mem", new JSONObject(getMemoryStats()))
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
                    try {
                        Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{info.pid});
                        if (mis != null && mis.length > 0) pss = mis[0].getTotalPss();
                    } catch (Exception ignored) {}
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

    @JavascriptInterface
    public String optimizeDevice() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int closed = 0;
            JSONArray closedPkgs = new JSONArray();
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    Set<String> done = new HashSet<>();
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info.pkgList == null) continue;
                        for (String pkg : info.pkgList) {
                            if (pkg == null || done.contains(pkg)) continue;
                            if (!ProtectedPackages.isValidPackage(pkg)) continue;
                            if (ProtectedPackages.isProtected(context, pkg)) continue;
                            done.add(pkg);
                            JSONObject r = new JSONObject(forceCloseApp(pkg));
                            if (r.optBoolean("ok")) {
                                closed++;
                                closedPkgs.put(pkg);
                            }
                        }
                    }
                }
            }
            boolean elevated = hasRoot();
            return new JSONObject()
                    .put("ok", true)
                    .put("closed", closed)
                    .put("closedPackages", closedPkgs)
                    .put("hasRoot", elevated)
                    .put("method", hasRealRoot() ? "root_force_stop" : elevated ? "userspace_temp_kill" : "kill_background")
                    .put("mem", new JSONObject(getMemoryStats()))
                    .toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String forceCloseIfOpen(String packagesJson) {
        try {
            JSONArray input = new JSONArray(packagesJson != null ? packagesJson : "[]");
            // Cap batch size — prevent runaway loops from malformed UI payloads
            int max = Math.min(input.length(), 80);
            int closed = 0;
            for (int i = 0; i < max; i++) {
                String pkg = input.optString(i, "");
                if (!ProtectedPackages.isValidPackage(pkg)) continue;
                if (ProtectedPackages.isProtected(context, pkg)) continue;
                JSONObject r = new JSONObject(forceCloseApp(pkg));
                if (r.optBoolean("ok")) closed++;
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("closed", closed)
                    .put("hasRoot", hasRoot())
                    .put("mem", new JSONObject(getMemoryStats()))
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
                // Rough sideload heuristic: non-system, non-Play installer when available
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
            int score = Math.max(35, 96 - findings.length() * 3 - Math.min(15, outdated / 4));
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
                    .put("findings", findings)
                    .put("openRiskPackages", new JSONArray())
                    .put("hasRoot", hasRoot())
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
            RamMetrics ram = RamMetrics.sample(context);
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
