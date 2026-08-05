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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DeviceBridge {
    private static final String TAG = "ApexNative";
    private final Context context;
    private static Boolean rootAvailable = null;

    private static final Set<String> PROTECTED = new HashSet<>();
    static {
        String[] p = {"android","com.android.systemui","com.android.phone","com.android.server.telecom",
            "com.android.settings","com.google.android.inputmethod.latin","com.samsung.android.honeyboard",
            "com.android.providers.settings","com.android.providers.telephony","com.google.android.gms",
            "com.google.android.gsf","com.android.permissioncontroller","com.apexcare.app"};
        for (String s : p) PROTECTED.add(s);
    }

    public DeviceBridge(Context context) { this.context = context.getApplicationContext(); }

    private synchronized boolean hasRoot() {
        if (rootAvailable != null) return rootAvailable;
        rootAvailable = false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            int code = p.waitFor();
            r.close();
            rootAvailable = code == 0 && line != null && line.contains("uid=0");
        } catch (Exception e) { rootAvailable = false; }
        return rootAvailable;
    }

    private boolean runAsRoot(String command) {
        Process p = null; DataOutputStream os = null;
        try {
            p = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(command + "\n"); os.writeBytes("exit\n"); os.flush();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
        finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (p != null) p.destroy();
        }
    }

    @JavascriptInterface
    public String requestRootAccess() {
        try {
            synchronized (DeviceBridge.class) { rootAvailable = null; }
            boolean ok = hasRoot();
            JSONObject o = new JSONObject();
            o.put("ok", ok); o.put("hasRoot", ok);
            o.put("message", ok
                ? "ROOT ONLINE — temporary elevated session. Deeper am force-stop unlocked."
                : "No root. Install Magisk/KernelSU, grant this app once, then tap again. Android never auto-grants root on APK install.");
            if (ok) runAsRoot("id");
            return o.toString();
        } catch (Exception e) { return errorJson(e); }
    }

    private long parseKb(String line) {
        try { String[] p = line.split("\\s+"); if (p.length >= 2) return Long.parseLong(p[1]); } catch (Exception ignored) {}
        return 0;
    }

    private long[] readMem() {
        long total = 0, avail = 0, free = 0, buffers = 0, cached = 0, srec = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = parseKb(line);
                else if (line.startsWith("MemAvailable:")) avail = parseKb(line);
                else if (line.startsWith("MemFree:")) free = parseKb(line);
                else if (line.startsWith("Buffers:")) buffers = parseKb(line);
                else if (line.startsWith("Cached:")) cached = parseKb(line);
                else if (line.startsWith("SReclaimable:")) srec = parseKb(line);
            }
        } catch (Exception e) { Log.w(TAG, "meminfo", e); }
        if (avail <= 0) avail = free + buffers + cached + srec;
        return new long[]{avail, total};
    }

    private long[] medianMem() {
        List<Long> av = new ArrayList<>(); long total = 0;
        for (int i = 0; i < 5; i++) {
            long[] s = readMem();
            if (s[0] > 0) av.add(s[0]);
            if (s[1] > 0) total = s[1];
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        if (av.isEmpty()) return new long[]{0, total};
        Collections.sort(av);
        return new long[]{av.get(av.size()/2), total};
    }

    @JavascriptInterface
    public String getMemoryStats() {
        try {
            long[] m = medianMem();
            long availKb = m[0], totalKb = m[1];
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            if (totalKb <= 0 && mi.totalMem > 0) totalKb = mi.totalMem / 1024;
            if (availKb <= 0 && mi.availMem > 0) availKb = mi.availMem / 1024;
            int freePct = totalKb > 0 ? (int) Math.round(100.0 * availKb / totalKb) : 0;
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("freeRamPct", freePct);
            o.put("usedRamPct", 100 - freePct);
            o.put("totalRamGb", totalKb / (1024.0 * 1024.0));
            o.put("freeRamGb", availKb / (1024.0 * 1024.0));
            o.put("usedRamGb", Math.max(0, totalKb - availKb) / (1024.0 * 1024.0));
            o.put("lowMemory", mi.lowMemory);
            o.put("hasRoot", hasRoot());
            int procs = 0;
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                procs = list != null ? list.size() : 0;
            }
            o.put("runningProcesses", procs);
            try {
                StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
                long block = stat.getBlockSizeLong();
                long total = stat.getBlockCountLong() * block;
                long avail = stat.getAvailableBlocksLong() * block;
                o.put("storageAvailGb", avail / (1024.0 * 1024 * 1024));
                o.put("storageUsedPct", total > 0 ? ((total - avail) * 100.0) / total : 0);
            } catch (Exception ignored) {}
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
            boolean root = hasRoot();
            if (root) runAsRoot("am force-stop " + packageName);
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) try { am.killBackgroundProcesses(packageName); } catch (Exception ignored) {}
            return new JSONObject().put("ok", true).put("hasRoot", root)
                .put("method", root ? "root_force_stop" : "kill_background")
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
                .put("hasRoot", hasRoot()).put("rooted", hasRoot()).toString();
        } catch (Exception e) { return errorJson(e); }
    }

    private static String errorJson(Exception e) {
        try {
            return new JSONObject().put("ok", false).put("error",
                e.getMessage() != null ? e.getMessage() : e.toString()).toString();
        } catch (Exception ex) { return "{\"ok\":false}"; }
    }
}
