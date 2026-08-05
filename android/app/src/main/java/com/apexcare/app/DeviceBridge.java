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
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ApexNative bridge. RAM uses multi-sample median MemAvailable from /proc/meminfo.
 * Force-close prefers root am force-stop.
 */
public class DeviceBridge {
    private static final String TAG = "ApexNative";
    private final Context context;

    private static final Set<String> PROTECTED = new HashSet<>();
    static {
        String[] p = {
            "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.samsung.android.incallui", "com.sec.android.app.launcher", "com.android.settings",
            "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
            "com.android.providers.settings", "com.android.providers.contacts",
            "com.android.providers.media", "com.android.providers.telephony",
            "com.samsung.android.provider.filterprovider", "com.apexcare.app"
        };
        for (String s : p) PROTECTED.add(s);
    }

    public DeviceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    private static class MeminfoSnapshot {
        long memTotalKb, memAvailableKb, memFreeKb, buffersKb, cachedKb, sReclaimableKb;
        long usableKb() {
            if (memAvailableKb > 0) return memAvailableKb;
            return memFreeKb + buffersKb + cachedKb + sReclaimableKb;
        }
    }

    private MeminfoSnapshot readMeminfoOnce() {
        MeminfoSnapshot s = new MeminfoSnapshot();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) s.memTotalKb = parseKb(line);
                else if (line.startsWith("MemAvailable:")) s.memAvailableKb = parseKb(line);
                else if (line.startsWith("MemFree:")) s.memFreeKb = parseKb(line);
                else if (line.startsWith("Buffers:")) s.buffersKb = parseKb(line);
                else if (line.startsWith("Cached:")) s.cachedKb = parseKb(line);
                else if (line.startsWith("SReclaimable:")) s.sReclaimableKb = parseKb(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "meminfo", e);
        }
        return s;
    }

    private long parseKb(String line) {
        try {
            String[] p = line.split("\\s+");
            if (p.length >= 2) return Long.parseLong(p[1]);
        } catch (Exception ignored) {}
        return 0;
    }

    /** 5-sample median of MemAvailable for stability / pixel-level accuracy. */
    private MeminfoSnapshot medianMeminfo() {
        List<Long> avails = new ArrayList<>();
        MeminfoSnapshot last = null;
        for (int i = 0; i < 5; i++) {
            last = readMeminfoOnce();
            if (last.usableKb() > 0) avails.add(last.usableKb());
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        }
        if (last == null) last = new MeminfoSnapshot();
        if (!avails.isEmpty()) {
            Collections.sort(avails);
            last.memAvailableKb = avails.get(avails.size() / 2);
        }
        return last;
    }

    private static String fmtGb(long kb) {
        return String.format(Locale.US, "%.1f", kb / (1024.0 * 1024.0));
    }

    @JavascriptInterface
    public String getMemoryInfo() {
        try {
            MeminfoSnapshot s = medianMeminfo();
            long totalKb = s.memTotalKb;
            long availKb = s.usableKb();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            long advertised = 0;
            if (Build.VERSION.SDK_INT >= 34 && mi.advertisedMem > 0) {
                advertised = mi.advertisedMem / 1024;
            }
            if (totalKb <= 0 && mi.totalMem > 0) totalKb = mi.totalMem / 1024;
            if (availKb <= 0 && mi.availMem > 0) availKb = mi.availMem / 1024;
            int freePct = totalKb > 0 ? (int) Math.round(100.0 * availKb / totalKb) : 0;
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("totalKb", totalKb);
            o.put("availableKb", availKb);
            o.put("freeRamPct", freePct);
            o.put("totalGb", fmtGb(totalKb));
            o.put("availableGb", fmtGb(availKb));
            o.put("advertisedGb", advertised > 0 ? fmtGb(advertised) : fmtGb(totalKb));
            o.put("availMemKb", mi.availMem / 1024);
            o.put("lowMemory", mi.lowMemory);
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getDiskInfo() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("totalBytes", total);
            o.put("availableBytes", avail);
            o.put("totalGb", String.format(Locale.US, "%.1f", total / (1024.0 * 1024 * 1024)));
            o.put("availableGb", String.format(Locale.US, "%.1f", avail / (1024.0 * 1024 * 1024)));
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private boolean isRooted() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su"};
        for (String p : paths) if (new File(p).exists()) return true;
        return false;
    }

    private boolean runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    @JavascriptInterface
    public String forceCloseApp(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "empty package").toString();
            }
            if (PROTECTED.contains(packageName) || packageName.startsWith("com.android.")
                    || packageName.startsWith("com.samsung.android.providers")) {
                return new JSONObject().put("ok", false).put("error", "protected").put("pkg", packageName).toString();
            }
            boolean root = isRooted();
            boolean stopped = false;
            if (root) {
                stopped = runSu("am force-stop " + packageName);
            }
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                try { am.killBackgroundProcesses(packageName); } catch (Exception ignored) {}
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("pkg", packageName);
            o.put("root", root);
            o.put("forceStop", stopped);
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getRunningProcesses() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = context.getPackageManager();
            JSONArray arr = new JSONArray();
            if (am == null) return new JSONObject().put("ok", true).put("apps", arr).toString();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) procs = Collections.emptyList();
            Set<String> seen = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.pkgList == null) continue;
                for (String pkg : info.pkgList) {
                    if (pkg == null || seen.contains(pkg)) continue;
                    seen.add(pkg);
                    JSONObject row = new JSONObject();
                    row.put("packageName", pkg);
                    row.put("pid", info.pid);
                    row.put("importance", info.importance);
                    String label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        label = pm.getApplicationLabel(ai).toString();
                    } catch (Exception ignored) {}
                    row.put("label", label);
                    long pssKb = 0;
                    try {
                        Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{info.pid});
                        if (mis != null && mis.length > 0) pssKb = mis[0].getTotalPss();
                    } catch (Exception ignored) {}
                    row.put("pssKb", pssKb);
                    row.put("pssMb", String.format(Locale.US, "%.1f", pssKb / 1024.0));
                    row.put("protected", PROTECTED.contains(pkg));
                    arr.put(row);
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("apps", arr);
            o.put("count", arr.length());
            o.put("root", isRooted());
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
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
                        if (info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                            if (info.pkgList == null) continue;
                            for (String pkg : info.pkgList) {
                                if (pkg == null || done.contains(pkg) || PROTECTED.contains(pkg)) continue;
                                if (pkg.startsWith("com.android.") || pkg.equals(context.getPackageName())) continue;
                                done.add(pkg);
                                forceCloseApp(pkg);
                                closed++;
                                closedPkgs.put(pkg);
                            }
                        }
                    }
                }
            }
            MeminfoSnapshot after = medianMeminfo();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("closed", closed);
            o.put("packages", closedPkgs);
            o.put("availableGb", fmtGb(after.usableKb()));
            o.put("freeRamPct", after.memTotalKb > 0
                    ? (int) Math.round(100.0 * after.usableKb() / after.memTotalKb) : 0);
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String scanInstalledApps() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            JSONArray arr = new JSONArray();
            for (ApplicationInfo ai : apps) {
                JSONObject row = new JSONObject();
                row.put("packageName", ai.packageName);
                row.put("label", pm.getApplicationLabel(ai).toString());
                row.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                row.put("enabled", ai.enabled);
                arr.put(row);
            }
            return new JSONObject().put("ok", true).put("apps", arr).put("count", arr.length()).toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("brand", Build.BRAND);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("release", Build.VERSION.RELEASE);
            o.put("rooted", isRooted());
            return o.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static String errorJson(Exception e) {
        try {
            return new JSONObject().put("ok", false).put("error", e.getMessage() != null ? e.getMessage() : e.toString()).toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
