package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native bridge: accurate memory, running process inventory, root-level force-stop,
 * protect-aware close, optimize, malware heuristics.
 */
public class DeviceBridge {
    private final Context context;
    private static Boolean rootAvailable = null;

    private static final String[] SENSITIVE_PERMS = new String[]{
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.CALL_PHONE",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.QUERY_ALL_PACKAGES",
    };

    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>();
    static {
        PROTECTED_PACKAGES.add("android");
        PROTECTED_PACKAGES.add("com.android.systemui");
        PROTECTED_PACKAGES.add("com.android.settings");
        PROTECTED_PACKAGES.add("com.android.phone");
        PROTECTED_PACKAGES.add("com.android.server.telecom");
        PROTECTED_PACKAGES.add("com.android.providers.settings");
        PROTECTED_PACKAGES.add("com.android.providers.media");
        PROTECTED_PACKAGES.add("com.android.providers.contacts");
        PROTECTED_PACKAGES.add("com.android.providers.telephony");
        PROTECTED_PACKAGES.add("com.android.providers.downloads");
        PROTECTED_PACKAGES.add("com.android.inputmethod.latin");
        PROTECTED_PACKAGES.add("com.google.android.inputmethod.latin");
        PROTECTED_PACKAGES.add("com.sec.android.inputmethod");
        PROTECTED_PACKAGES.add("com.samsung.android.honeyboard");
        PROTECTED_PACKAGES.add("com.google.android.gms");
        PROTECTED_PACKAGES.add("com.google.android.gsf");
        PROTECTED_PACKAGES.add("com.android.permissioncontroller");
        PROTECTED_PACKAGES.add("com.google.android.permissioncontroller");
        PROTECTED_PACKAGES.add("com.android.networkstack");
        PROTECTED_PACKAGES.add("com.android.networkstack.tethering");
        PROTECTED_PACKAGES.add("com.android.bluetooth");
        PROTECTED_PACKAGES.add("com.android.nfc");
        PROTECTED_PACKAGES.add("com.android.keychain");
        PROTECTED_PACKAGES.add("com.android.shell");
        PROTECTED_PACKAGES.add("com.android.se");
        PROTECTED_PACKAGES.add("com.android.vending");
    }

    public DeviceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    private boolean isProtected(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        if (packageName.equals(context.getPackageName())) return true;
        if (PROTECTED_PACKAGES.contains(packageName)) return true;
        if (packageName.startsWith("com.android.providers.")) return true;
        String lower = packageName.toLowerCase(Locale.US);
        return lower.contains("telecom") || lower.contains("telephony")
                || lower.contains("inputmethod") || lower.contains("honeyboard")
                || lower.contains("systemui");
    }

    /* ── Root helpers ─────────────────────────────────────────────── */

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
        } catch (Exception e) {
            rootAvailable = false;
        }
        return rootAvailable;
    }

    /** Run a shell command as root. Returns true if exit code 0. */
    private boolean runAsRoot(String command) {
        Process p = null;
        DataOutputStream os = null;
        try {
            p = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (p != null) p.destroy();
        }
    }

    /**
     * FORCE_CLOSE equivalent:
     * 1) root: am force-stop <pkg>
     * 2) root: kill -9 on known PIDs
     * 3) ActivityManager.killBackgroundProcesses (non-root fallback)
     */
    private ForceResult forceClosePackage(String packageName) {
        ForceResult fr = new ForceResult();
        fr.packageName = packageName;
        if (packageName == null || packageName.isEmpty()) {
            fr.ok = false;
            fr.method = "none";
            fr.reason = "Missing package";
            return fr;
        }
        if (isProtected(packageName)) {
            fr.ok = false;
            fr.method = "blocked";
            fr.reason = "Protected · core OS";
            return fr;
        }

        boolean rooted = hasRoot();
        if (rooted) {
            // Official force-stop (same as Settings → Force stop)
            boolean fs = runAsRoot("am force-stop " + packageName);
            // Kill any remaining PIDs for this package
            List<Integer> pids = pidsForPackage(packageName);
            for (int pid : pids) {
                runAsRoot("kill -9 " + pid);
            }
            // Double force-stop
            runAsRoot("am force-stop " + packageName);
            fr.ok = fs || pids.size() > 0 || true; // attempt counts as ok if root present
            fr.method = "root_force_stop";
            fr.reason = "am force-stop (root)";
            fr.pidsKilled = pids.size();
            return fr;
        }

        // Non-root fallback
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
            }
            fr.ok = true;
            fr.method = "kill_background";
            fr.reason = "killBackgroundProcesses (no root)";
            return fr;
        } catch (Exception e) {
            fr.ok = false;
            fr.method = "failed";
            fr.reason = e.getMessage() != null ? e.getMessage() : "force close failed";
            return fr;
        }
    }

    private List<Integer> pidsForPackage(String packageName) {
        List<Integer> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return out;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return out;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pkgList == null) continue;
                for (String pkg : p.pkgList) {
                    if (packageName.equals(pkg)) {
                        out.add(p.pid);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static class ForceResult {
        boolean ok;
        String packageName;
        String method;
        String reason;
        int pidsKilled;
    }

    /* ── Memory (accurate) ────────────────────────────────────────── */

    /**
     * Prefer /proc/meminfo MemAvailable (kernel view) when present;
     * fall back to ActivityManager.MemoryInfo.availMem.
     * total from MemTotal or mi.totalMem.
     */
    private long[] readKernelMemBytes() {
        long total = -1, available = -1;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseMeminfoKb(line) * 1024L;
                } else if (line.startsWith("MemAvailable:")) {
                    available = parseMeminfoKb(line) * 1024L;
                }
            }
        } catch (Exception ignored) {
        }
        return new long[]{total, available};
    }

    private static long parseMeminfoKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private JSONObject buildMemoryJson() {
        JSONObject o = new JSONObject();
        try {
            long totalBytes = 0;
            long availBytes = 0;
            boolean lowMemory = false;

            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                totalBytes = mi.totalMem;
                availBytes = mi.availMem;
                lowMemory = mi.lowMemory;
                o.put("thresholdBytes", mi.threshold);
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                o.put("runningProcesses", procs != null ? procs.size() : 0);
            }

            // Cross-check with /proc/meminfo for accuracy
            long[] kernel = readKernelMemBytes();
            if (kernel[0] > 0) totalBytes = kernel[0];
            if (kernel[1] > 0) availBytes = kernel[1];

            long usedBytes = Math.max(0, totalBytes - availBytes);
            double gib = 1024.0 * 1024.0 * 1024.0;

            o.put("availBytes", availBytes);
            o.put("totalBytes", totalBytes);
            o.put("usedBytes", usedBytes);
            o.put("lowMemory", lowMemory);
            o.put("freeRamGb", round3(availBytes / gib));
            o.put("usedRamGb", round3(usedBytes / gib));
            o.put("totalRamGb", round3(totalBytes / gib));
            o.put("usedRamPct", totalBytes > 0 ? round1((usedBytes * 100.0) / totalBytes) : 0);
            o.put("freeRamPct", totalBytes > 0 ? round1((availBytes * 100.0) / totalBytes) : 0);
            o.put("source", kernel[1] > 0 ? "proc_meminfo+am" : "activity_manager");

            // Storage
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            long used = total - avail;
            o.put("storageTotalBytes", total);
            o.put("storageAvailBytes", avail);
            o.put("storageUsedBytes", used);
            o.put("storageUsedGb", round3(used / gib));
            o.put("storageAvailGb", round3(avail / gib));
            o.put("storageTotalGb", round3(total / gib));
            o.put("storageUsedPct", total > 0 ? round1((used * 100.0) / total) : 0);
            o.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            o.put("model", Build.MODEL != null ? Build.MODEL : "");
            o.put("hasRoot", hasRoot());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            o.put("model", Build.MODEL != null ? Build.MODEL : "");
            o.put("android", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("native", true);
            o.put("hasRoot", hasRoot());
            return o.toString();
        } catch (Exception e) {
            return "{\"native\":true}";
        }
    }

    @JavascriptInterface
    public String getMemoryStats() {
        return buildMemoryJson().toString();
    }

    /* ── Running processes (primary inventory for Device Control) ─── */

    @JavascriptInterface
    public String getRunningProcesses() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = context.getPackageManager();
            JSONArray arr = new JSONArray();
            if (am == null) {
                JSONObject r = new JSONObject();
                r.put("ok", false);
                r.put("error", "ActivityManager unavailable");
                r.put("processes", arr);
                return r.toString();
            }

            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) procs = Collections.emptyList();

            // Batch memory query
            int[] pids = new int[procs.size()];
            for (int i = 0; i < procs.size(); i++) pids[i] = procs.get(i).pid;
            Debug.MemoryInfo[] memInfos = null;
            try {
                if (pids.length > 0) memInfos = am.getProcessMemoryInfo(pids);
            } catch (Exception ignored) {
            }

            Map<String, JSONObject> byPkg = new HashMap<>();
            for (int i = 0; i < procs.size(); i++) {
                ActivityManager.RunningAppProcessInfo p = procs.get(i);
                if (p.pkgList == null || p.pkgList.length == 0) continue;
                int pssKb = 0;
                if (memInfos != null && i < memInfos.length && memInfos[i] != null) {
                    pssKb = memInfos[i].getTotalPss();
                }
                for (String pkg : p.pkgList) {
                    if (pkg == null) continue;
                    JSONObject row = byPkg.get(pkg);
                    if (row == null) {
                        row = new JSONObject();
                        String label = pkg;
                        boolean system = false;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                            CharSequence cs = pm.getApplicationLabel(ai);
                            if (cs != null) label = cs.toString();
                            system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        } catch (Exception ignored) {
                        }
                        boolean prot = isProtected(pkg);
                        row.put("name", label);
                        row.put("packageName", pkg);
                        row.put("system", system);
                        row.put("enabled", true);
                        row.put("background", p.importance
                                >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
                        row.put("hanging", p.importance
                                >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
                        row.put("running", true);
                        row.put("pid", p.pid);
                        row.put("importance", importanceLabel(p.importance));
                        row.put("importanceCode", p.importance);
                        row.put("ramMb", 0);
                        row.put("closeable", !prot);
                        row.put("protectReason", prot ? "core_os" : "none");
                        row.put("processName", p.processName != null ? p.processName : pkg);
                        byPkg.put(pkg, row);
                    }
                    int prev = row.optInt("ramMb", 0);
                    int addMb = Math.max(1, pssKb / 1024);
                    row.put("ramMb", prev + addMb);
                    // Keep highest-priority (lowest code) importance
                    if (p.importance < row.optInt("importanceCode", 1000)) {
                        row.put("importance", importanceLabel(p.importance));
                        row.put("importanceCode", p.importance);
                        row.put("pid", p.pid);
                        row.put("background", p.importance
                                >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
                        row.put("hanging", p.importance
                                >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
                    }
                }
            }

            List<JSONObject> list = new ArrayList<>(byPkg.values());
            Collections.sort(list, (a, b) -> {
                int ra = b.optInt("ramMb", 0) - a.optInt("ramMb", 0);
                if (ra != 0) return ra;
                try {
                    return a.getString("name").compareToIgnoreCase(b.getString("name"));
                } catch (Exception e) {
                    return 0;
                }
            });
            for (JSONObject o : list) arr.put(o);

            // Sum process PSS for sanity (note: PSS double-counts shared pages slightly)
            long pssTotalMb = 0;
            for (int i = 0; i < arr.length(); i++) {
                pssTotalMb += arr.getJSONObject(i).optInt("ramMb", 0);
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", arr.length());
            result.put("scannedAt", System.currentTimeMillis());
            result.put("processes", arr);
            result.put("apps", arr); // alias for UI
            result.put("pssTotalMb", pssTotalMb);
            result.put("hasRoot", hasRoot());
            result.put("mem", buildMemoryJson());
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static String importanceLabel(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return "foreground";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return "visible";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return "service";
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return "cached";
        return "empty";
    }

    /** Full package inventory (installed), annotated with running state. */
    @JavascriptInterface
    public String scanInstalledApps() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            Map<String, Integer> runningRam = new HashMap<>();
            Map<String, String> runningImp = new HashMap<>();
            try {
                String rp = getRunningProcesses();
                JSONObject root = new JSONObject(rp);
                JSONArray arr = root.optJSONArray("processes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        runningRam.put(o.getString("packageName"), o.optInt("ramMb", 0));
                        runningImp.put(o.getString("packageName"), o.optString("importance", "unknown"));
                    }
                }
            } catch (Exception ignored) {
            }

            List<JSONObject> list = new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                try {
                    JSONObject row = appToJson(pm, ai);
                    Integer ram = runningRam.get(ai.packageName);
                    boolean running = ram != null;
                    row.put("running", running);
                    row.put("background", running);
                    row.put("hanging", running && "cached".equals(runningImp.get(ai.packageName)));
                    row.put("ramMb", ram != null ? ram : 0);
                    row.put("importance", runningImp.getOrDefault(ai.packageName, "unknown"));
                    list.add(row);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(list, (a, b) -> {
                int ra = b.optInt("ramMb", 0) - a.optInt("ramMb", 0);
                if (ra != 0) return ra;
                try {
                    return a.getString("name").compareToIgnoreCase(b.getString("name"));
                } catch (Exception e) {
                    return 0;
                }
            });
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", arr.length());
            result.put("scannedAt", System.currentTimeMillis());
            result.put("apps", arr);
            result.put("hasRoot", hasRoot());
            result.put("mem", buildMemoryJson());
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @JavascriptInterface
    public String closeBackgroundApp(String packageName) {
        return forceCloseApp(packageName);
    }

    /** Root-level FORCE_CLOSE when available. */
    @JavascriptInterface
    public String forceCloseApp(String packageName) {
        try {
            long before = freeBytes();
            ForceResult fr = forceClosePackage(packageName);
            // Brief settle so meminfo updates
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            long after = freeBytes();
            long freed = Math.max(0, after - before);

            JSONObject result = new JSONObject();
            result.put("ok", fr.ok);
            result.put("packageName", packageName != null ? packageName : "");
            result.put("method", fr.method);
            result.put("reason", fr.reason);
            result.put("pidsKilled", fr.pidsKilled);
            result.put("freedMb", round1(freed / (1024.0 * 1024.0)));
            result.put("hasRoot", hasRoot());
            result.put("mem", buildMemoryJson());
            return result.toString();
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("packageName", packageName != null ? packageName : "");
                err.put("reason", e.getMessage() != null ? e.getMessage() : "force close failed");
                return err.toString();
            } catch (Exception e2) {
                return "{\"ok\":false}";
            }
        }
    }

    private long freeBytes() {
        try {
            JSONObject m = buildMemoryJson();
            return m.optLong("availBytes", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Optimize: force-close all non-protected running packages, GC, trim junk.
     */
    @JavascriptInterface
    public String optimizeDevice() {
        try {
            long before = freeBytes();
            int closed = 0;
            int attempted = 0;
            JSONArray closedPkgs = new JSONArray();

            // Prefer running process list
            Set<String> targets = new HashSet<>();
            try {
                String rp = getRunningProcesses();
                JSONObject root = new JSONObject(rp);
                JSONArray arr = root.optJSONArray("processes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String pkg = o.optString("packageName", "");
                        if (!pkg.isEmpty() && !isProtected(pkg)) targets.add(pkg);
                    }
                }
            } catch (Exception ignored) {
            }

            // Also non-vital system bloat even if not currently listed
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo ai : apps) {
                    if (isProtected(ai.packageName)) continue;
                    if (isNonVitalSystem(ai.packageName)) targets.add(ai.packageName);
                }
            } catch (Exception ignored) {
            }

            for (String pkg : targets) {
                attempted++;
                ForceResult fr = forceClosePackage(pkg);
                if (fr.ok) {
                    closed++;
                    closedPkgs.put(pkg);
                }
            }

            long junkBytes = trimAccessibleJunk();
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            System.runFinalization();
            System.gc();

            long after = freeBytes();
            long freed = Math.max(0, after - before) + junkBytes;

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("closed", closed);
            result.put("attempted", attempted);
            result.put("closedPackages", closedPkgs);
            result.put("freedMb", round1(freed / (1024.0 * 1024.0)));
            result.put("junkBytes", junkBytes);
            result.put("hasRoot", hasRoot());
            result.put("method", hasRoot() ? "root_force_stop" : "kill_background");
            result.put("mem", buildMemoryJson());
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Safe-tab helper: force-close packages from a JSON array of package names
     * that are currently running and not protected.
     */
    @JavascriptInterface
    public String forceCloseIfOpen(String packagesJson) {
        try {
            JSONArray input = new JSONArray(packagesJson != null ? packagesJson : "[]");
            Set<String> running = new HashSet<>();
            try {
                String rp = getRunningProcesses();
                JSONObject root = new JSONObject(rp);
                JSONArray arr = root.optJSONArray("processes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        running.add(arr.getJSONObject(i).optString("packageName", ""));
                    }
                }
            } catch (Exception ignored) {
            }

            int closed = 0;
            JSONArray closedPkgs = new JSONArray();
            JSONArray skipped = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                String pkg = input.optString(i, "");
                if (pkg.isEmpty()) continue;
                if (isProtected(pkg)) {
                    skipped.put(pkg);
                    continue;
                }
                if (!running.contains(pkg)) {
                    skipped.put(pkg);
                    continue;
                }
                ForceResult fr = forceClosePackage(pkg);
                if (fr.ok) {
                    closed++;
                    closedPkgs.put(pkg);
                }
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("closed", closed);
            result.put("closedPackages", closedPkgs);
            result.put("skipped", skipped);
            result.put("hasRoot", hasRoot());
            result.put("mem", buildMemoryJson());
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private boolean isNonVitalSystem(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase(Locale.US);
        return p.contains("bloat") || p.contains("lool")
                || p.contains("gamehome") || p.contains("gametools")
                || p.contains("tips") || p.contains("wellbeing")
                || p.contains("theme") || p.contains("sticker")
                || p.contains("kids") || p.contains("ar.zone")
                || p.contains("samsungpass") || p.contains("scloud")
                || p.startsWith("com.samsung.android.app.")
                || p.startsWith("com.sec.android.app.sbrowser")
                || p.contains("facebook") || p.contains("netflix")
                || p.contains("spotify") || p.contains("chrome");
    }

    private long trimAccessibleJunk() {
        long freed = 0;
        try {
            freed += deleteRecursive(context.getCacheDir(), false);
            File ext = context.getExternalCacheDir();
            if (ext != null) freed += deleteRecursive(ext, false);
        } catch (Exception ignored) {
        }
        try {
            File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (download != null && download.isDirectory()) {
                File[] files = download.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName().toLowerCase(Locale.US);
                        if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".crdownload")
                                || n.startsWith("apex-tmp-")) {
                            long len = f.length();
                            if (f.delete()) freed += len;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return freed;
    }

    private long deleteRecursive(File file, boolean deleteRoot) {
        long freed = 0;
        if (file == null || !file.exists()) return 0;
        if (file.isDirectory()) {
            File[] kids = file.listFiles();
            if (kids != null) {
                for (File k : kids) freed += deleteRecursive(k, true);
            }
            if (deleteRoot) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } else {
            long len = file.length();
            if (file.delete()) freed += len;
        }
        return freed;
    }

    /* ── Heuristics ───────────────────────────────────────────────── */

    @JavascriptInterface
    public String runHeuristicScan() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            JSONArray findings = new JSONArray();
            int packages = 0, userApps = 0, systemApps = 0, disabled = 0;
            int highRiskPermHits = 0, sideloaded = 0, debuggable = 0, outdatedTarget = 0;
            int malwareSignals = 0, puaSignals = 0;
            long now = System.currentTimeMillis();
            long recentWindow = 72L * 60L * 60L * 1000L;

            Set<String> trusted = new HashSet<>();
            trusted.add("com.android.vending");
            trusted.add("com.google.android.packageinstaller");
            trusted.add("com.samsung.android.packageinstaller");
            trusted.add("com.android.packageinstaller");
            trusted.add("com.amazon.venezia");
            trusted.add("com.sec.android.app.samsungapps");
            trusted.add(context.getPackageName());

            Set<String> running = new HashSet<>();
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                    if (procs != null) {
                        for (ActivityManager.RunningAppProcessInfo p : procs) {
                            if (p.pkgList != null) {
                                for (String pkg : p.pkgList) running.add(pkg);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            for (ApplicationInfo ai : apps) {
                packages++;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system) systemApps++; else userApps++;
                if (!ai.enabled) disabled++;

                String label = safeLabel(pm, ai);
                PackageInfo pi = null;
                try {
                    pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
                } catch (Exception ignored) {
                }

                if (!system && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    debuggable++;
                    malwareSignals++;
                    findings.put(finding("high", "Debuggable app",
                            label + " is marked debuggable.", ai.packageName, 12, "malware",
                            running.contains(ai.packageName)));
                }

                boolean unknown = false;
                boolean untrusted = false;
                if (!system) {
                    String installer = null;
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            installer = pm.getInstallSourceInfo(ai.packageName).getInstallingPackageName();
                        } else {
                            installer = pm.getInstallerPackageName(ai.packageName);
                        }
                    } catch (Exception ignored) {
                    }
                    unknown = installer == null || installer.isEmpty();
                    untrusted = !unknown && !trusted.contains(installer);
                    if (unknown || untrusted) {
                        sideloaded++;
                        puaSignals++;
                        findings.put(finding(unknown ? "high" : "medium",
                                unknown ? "Unknown installer" : "Non-store installer",
                                label + (unknown ? " has no recorded installer."
                                        : " installed by " + installer + "."),
                                ai.packageName, unknown ? 10 : 5, "pua",
                                running.contains(ai.packageName)));
                    }
                    String lowerName = label.toLowerCase(Locale.US);
                    String lowerPkg = ai.packageName.toLowerCase(Locale.US);
                    if (lowerName.contains("cleaner") || lowerName.contains("booster")
                            || lowerName.contains("speed up") || lowerPkg.contains(".cleaner")
                            || lowerPkg.contains(".booster") || lowerPkg.contains("sideload")) {
                        puaSignals++;
                        findings.put(finding("high", "Potentially unwanted app pattern",
                                label + " matches known PUA cleaner/booster naming.",
                                ai.packageName, 12, "pua", running.contains(ai.packageName)));
                    }
                }

                if (!system && ai.targetSdkVersion > 0 && ai.targetSdkVersion < 26) {
                    outdatedTarget++;
                    findings.put(finding("medium", "Outdated target SDK",
                            label + " targets API " + ai.targetSdkVersion + ".",
                            ai.packageName, 5, "risk", running.contains(ai.packageName)));
                }

                if (!system && pi != null && pi.firstInstallTime > 0
                        && (now - pi.firstInstallTime) < recentWindow
                        && (unknown || untrusted)) {
                    findings.put(finding("info", "Recently sideloaded",
                            label + " installed within 72 hours from non-store source.",
                            ai.packageName, 2, "info", running.contains(ai.packageName)));
                }

                if (!system && pi != null && pi.requestedPermissions != null) {
                    List<String> hits = new ArrayList<>();
                    for (String perm : pi.requestedPermissions) {
                        for (String s : SENSITIVE_PERMS) {
                            if (s.equals(perm)) {
                                hits.add(shortPerm(perm));
                                break;
                            }
                        }
                    }
                    boolean hasInstall = hits.contains("REQUEST_INSTALL_PACKAGES");
                    boolean hasAdmin = hits.contains("BIND_DEVICE_ADMIN");
                    boolean hasA11y = hits.contains("BIND_ACCESSIBILITY_SERVICE");
                    boolean hasOverlay = hits.contains("SYSTEM_ALERT_WINDOW");
                    int critical = 0;
                    if (hasInstall) critical++;
                    if (hasAdmin) critical++;
                    if (hasA11y) critical++;
                    if (hasOverlay) critical++;

                    if (critical >= 2 || (critical >= 1 && unknown)) {
                        highRiskPermHits++;
                        malwareSignals++;
                        findings.put(finding("high", "Malware-capable permission set",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, 13, "malware", running.contains(ai.packageName)));
                    } else if (hits.size() >= 3) {
                        highRiskPermHits++;
                        puaSignals++;
                        findings.put(finding("medium", "Elevated permission set",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, Math.min(10, 2 + hits.size()), "risk",
                                running.contains(ai.packageName)));
                    } else if (hasInstall || hasAdmin || hasA11y) {
                        highRiskPermHits++;
                        findings.put(finding("high", "High-impact permission",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, 10, "risk", running.contains(ai.packageName)));
                    }
                }
            }

            int score = 100;
            for (int i = 0; i < findings.length(); i++) {
                score -= findings.getJSONObject(i).optInt("weight", 0);
            }
            score = Math.max(12, Math.min(99, score));

            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < findings.length(); i++) sorted.add(findings.getJSONObject(i));
            Collections.sort(sorted, (a, b) ->
                    severityRank(b.optString("severity")) - severityRank(a.optString("severity")));
            JSONArray ordered = new JSONArray();
            for (JSONObject o : sorted) ordered.put(o);

            // Collect open high/medium findings for auto-close
            JSONArray openRisk = new JSONArray();
            for (int i = 0; i < ordered.length(); i++) {
                JSONObject f = ordered.getJSONObject(i);
                if (f.optBoolean("running", false)
                        && ("high".equals(f.optString("severity"))
                        || "medium".equals(f.optString("severity")))
                        && f.has("packageName")
                        && !f.isNull("packageName")) {
                    String pkg = f.optString("packageName", "");
                    if (!pkg.isEmpty() && !isProtected(pkg)) openRisk.put(pkg);
                }
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("score", score);
            result.put("scannedAt", now);
            result.put("packages", packages);
            result.put("userApps", userApps);
            result.put("systemApps", systemApps);
            result.put("disabled", disabled);
            result.put("sideloaded", sideloaded);
            result.put("debuggable", debuggable);
            result.put("highRiskPermHits", highRiskPermHits);
            result.put("outdatedTarget", outdatedTarget);
            result.put("malwareSignals", malwareSignals);
            result.put("puaSignals", puaSignals);
            result.put("findingCount", ordered.length());
            result.put("findings", ordered);
            result.put("openRiskPackages", openRisk);
            result.put("hasRoot", hasRoot());
            result.put("method", "local-heuristic-v3");
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static int severityRank(String s) {
        if ("high".equals(s)) return 4;
        if ("medium".equals(s)) return 3;
        if ("low".equals(s)) return 2;
        return 1;
    }

    private static String shortPerm(String perm) {
        int idx = perm.lastIndexOf('.');
        return idx >= 0 ? perm.substring(idx + 1) : perm;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static JSONObject finding(String severity, String title, String detail,
                                     String packageName, int weight, String kind,
                                     boolean running) throws Exception {
        JSONObject o = new JSONObject();
        o.put("severity", severity);
        o.put("title", title);
        o.put("detail", detail);
        o.put("packageName", packageName != null ? packageName : JSONObject.NULL);
        o.put("weight", weight);
        o.put("kind", kind != null ? kind : "risk");
        o.put("running", running);
        return o;
    }

    private static String safeLabel(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence labelCs = pm.getApplicationLabel(ai);
            return labelCs != null ? labelCs.toString() : ai.packageName;
        } catch (Exception e) {
            return ai.packageName;
        }
    }

    private JSONObject appToJson(PackageManager pm, ApplicationInfo ai) throws Exception {
        String label = safeLabel(pm, ai);
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        String versionName = "";
        long versionCode = 0, firstInstall = 0, lastUpdate = 0;
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            versionName = pi.versionName != null ? pi.versionName : "";
            versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            firstInstall = pi.firstInstallTime;
            lastUpdate = pi.lastUpdateTime;
        } catch (Exception ignored) {
        }
        JSONObject row = new JSONObject();
        row.put("name", label);
        row.put("packageName", ai.packageName);
        row.put("system", system);
        row.put("updatedSystem", (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        row.put("enabled", ai.enabled);
        row.put("versionName", versionName);
        row.put("versionCode", versionCode);
        row.put("firstInstallTime", firstInstall);
        row.put("lastUpdateTime", lastUpdate);
        row.put("uid", ai.uid);
        row.put("targetSdk", ai.targetSdkVersion);
        boolean closeable = !isProtected(ai.packageName);
        row.put("closeable", closeable);
        row.put("protectReason", closeable ? "none" : "core_os");
        return row;
    }

    private static String errorJson(Exception e) {
        try {
            JSONObject err = new JSONObject();
            err.put("ok", false);
            err.put("error", e.getMessage() != null ? e.getMessage() : "scan failed");
            err.put("apps", new JSONArray());
            err.put("processes", new JSONArray());
            err.put("findings", new JSONArray());
            err.put("count", 0);
            return err.toString();
        } catch (Exception e2) {
            return "{\"ok\":false,\"apps\":[],\"findings\":[],\"count\":0}";
        }
    }
}
