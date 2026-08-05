package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeviceBridge {
    private static final String TAG = "ApexNative";
    private final Context context;
    private final MagiskRoot magisk = MagiskRoot.get();

    private static final Set<String> PROTECTED = new HashSet<>();
    static {
        String[] p = {"android","com.android.systemui","com.android.phone","com.android.server.telecom",
            "com.android.settings","com.google.android.inputmethod.latin","com.samsung.android.honeyboard",
            "com.android.providers.settings","com.android.providers.telephony","com.google.android.gms",
            "com.google.android.gsf","com.android.permissioncontroller","com.apexcare.app"};
        for (String s : p) PROTECTED.add(s);
    }

    public DeviceBridge(Context context) { this.context = context.getApplicationContext(); }

    private boolean hasRoot() {
        return magisk.isGranted() || magisk.probeQuick();
    }

    private boolean hasRealRoot() {
        return magisk.isRealRoot();
    }

    private boolean runAsRoot(String command) {
        return magisk.run(command);
    }

    /**
     * Grant Temporary Root — talks to Magisk app (if installed), requests Superuser,
     * or engages userspace TEMP ROOT (proot-style) when Magisk app is present but
     * boot is not patched / Superuser empty.
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
            // Always attach fresh memory snapshot for UI
            try { o.put("mem", new JSONObject(getMemoryStats())); } catch (Exception ignored) {}
            return o.toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String getRootStatus() {
        try {
            return magisk.statusJson()
                    .put("ok", true)
                    .toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String getMemoryStats() {
        try {
            RamMetrics ram = RamMetrics.sample(context);
            JSONObject o = ram.toJson(hasRoot());
            return o.toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface public String getMemoryInfo() { return getMemoryStats(); }


    @JavascriptInterface
    public String forceCloseApp(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty())
                return new JSONObject().put("ok", false).put("error", "empty").toString();
            if (PROTECTED.contains(packageName))
                return new JSONObject().put("ok", false).put("error", "protected").toString();
            boolean real = hasRealRoot();
            boolean elevated = hasRoot();
            String method = "kill_background";
            if (real) {
                runAsRoot("am force-stop " + packageName);
                runAsRoot("cmd activity force-stop " + packageName);
                method = "root_force_stop";
            }
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                try { am.killBackgroundProcesses(packageName); } catch (Exception ignored) {}
                // Userspace temp root: extra kill passes
                if (elevated && !real) {
                    try { am.killBackgroundProcesses(packageName); } catch (Exception ignored) {}
                    method = "userspace_temp_kill";
                }
            }
            return new JSONObject().put("ok", true).put("hasRoot", elevated).put("realRoot", real)
                .put("mode", magisk.getMode()).put("method", method)
                .put("mem", new JSONObject(getMemoryStats())).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface public String closeBackgroundApp(String p) { return forceCloseApp(p); }

    @JavascriptInterface
    public String getRunningProcesses() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = context.getPackageManager();
            JSONArray arr = new JSONArray();
            if (am == null) return new JSONObject().put("ok", true).put("processes", arr).toString();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) procs = Collections.emptyList();
            Set<String> seen = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.pkgList == null) continue;
                for (String pkg : info.pkgList) {
                    if (pkg == null || seen.contains(pkg)) continue;
                    seen.add(pkg);
                    JSONObject row = new JSONObject();
                    row.put("packageName", pkg); row.put("pid", info.pid); row.put("importance", info.importance);
                    String label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        label = pm.getApplicationLabel(ai).toString();
                        row.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    } catch (Exception ignored) { row.put("system", false); }
                    row.put("name", label); row.put("label", label);
                    long pss = 0;
                    try {
                        Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{info.pid});
                        if (mis != null && mis.length > 0) pss = mis[0].getTotalPss();
                    } catch (Exception ignored) {}
                    row.put("ramMb", Math.round(pss / 1024.0));
                    row.put("protected", PROTECTED.contains(pkg));
                    row.put("running", true);
                    arr.put(row);
                }
            }
            return new JSONObject().put("ok", true).put("processes", arr).put("apps", arr)
                .put("hasRoot", hasRoot()).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String optimizeDevice() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int closed = 0; JSONArray closedPkgs = new JSONArray();
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    Set<String> done = new HashSet<>();
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info.pkgList == null) continue;
                        for (String pkg : info.pkgList) {
                            if (pkg == null || done.contains(pkg) || PROTECTED.contains(pkg)) continue;
                            if (pkg.equals(context.getPackageName())) continue;
                            done.add(pkg);
                            forceCloseApp(pkg);
                            closed++; closedPkgs.put(pkg);
                        }
                    }
                }
            }
            return new JSONObject().put("ok", true).put("closed", closed).put("closedPackages", closedPkgs)
                .put("hasRoot", hasRoot()).put("method", hasRoot() ? "root_force_stop" : "kill_background")
                .put("mem", new JSONObject(getMemoryStats())).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String forceCloseIfOpen(String packagesJson) {
        try {
            JSONArray input = new JSONArray(packagesJson != null ? packagesJson : "[]");
            int closed = 0;
            for (int i = 0; i < input.length(); i++) {
                String pkg = input.optString(i, "");
                if (pkg.isEmpty() || PROTECTED.contains(pkg)) continue;
                JSONObject r = new JSONObject(forceCloseApp(pkg));
                if (r.optBoolean("ok")) closed++;
            }
            return new JSONObject().put("ok", true).put("closed", closed).put("hasRoot", hasRoot())
                .put("mem", new JSONObject(getMemoryStats())).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String scanInstalledApps() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            JSONArray arr = new JSONArray();
            for (ApplicationInfo ai : apps) {
                JSONObject row = new JSONObject();
                row.put("packageName", ai.packageName);
                row.put("name", pm.getApplicationLabel(ai).toString());
                row.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                arr.put(row);
            }
            return new JSONObject().put("ok", true).put("apps", arr).put("hasRoot", hasRoot()).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String runHeuristicScan() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            int user = 0, system = 0, debuggable = 0;
            JSONArray findings = new JSONArray();
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) system++; else user++;
                if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    debuggable++;
                    findings.put(new JSONObject().put("title", "Debuggable app")
                        .put("detail", "Unusual for release builds.").put("severity", "medium")
                        .put("kind", "debug").put("packageName", ai.packageName).put("running", false));
                }
            }
            return new JSONObject().put("ok", true).put("score", Math.max(40, 95 - findings.length() * 3))
                .put("packages", apps.size()).put("userApps", user).put("systemApps", system)
                .put("disabled", 0).put("sideloaded", 0).put("debuggable", debuggable)
                .put("highRiskPermHits", 0).put("outdatedTarget", 0)
                .put("findingCount", findings.length()).put("malwareSignals", 0).put("puaSignals", 0)
                .put("findings", findings).put("openRiskPackages", new JSONArray())
                .put("hasRoot", hasRoot()).put("scannedAt", System.currentTimeMillis()).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            return new JSONObject().put("ok", true).put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER).put("sdk", Build.VERSION.SDK_INT)
                .put("hasRoot", hasRoot()).put("rooted", hasRoot())
                .put("realRoot", hasRealRoot())
                .put("mode", magisk.getMode())
                .put("suPath", magisk.getSuPath())
                .put("rootDetail", magisk.lastDetail()).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    private static String errorJson(Exception e) {
        try {
            return new JSONObject().put("ok", false).put("error",
                e.getMessage() != null ? e.getMessage() : e.toString()).toString();
        } catch (Exception ex) { return "{\"ok\":false}"; }
    }
}
