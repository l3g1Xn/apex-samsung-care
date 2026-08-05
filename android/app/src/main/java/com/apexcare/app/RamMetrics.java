package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device Care-style RAM scanner for Apex Care (Samsung One UI / Android 14+).
 *
 * Kernel MemTotal / ActivityManager.totalMem report usable RAM, typically
 * ~0.8-1.5 GB below advertised physical capacity (firmware, GPU, modem, CMA).
 * Samsung Device Care shows marketed total (e.g. 12 GB).
 *
 * Strategy:
 *   1) Scan MemTotal + AM.totalMem (hardware usable)
 *   2) Map usable total to nearest marketing tier (4/6/8/12/16/24/32 GB)
 *   3) free%/used% use marketed total so totals match device label
 *   4) available from MemAvailable / AM.availMem (live)
 */
public final class RamMetrics {
    private static final String TAG = "ApexRam";
    private static final String PREFS = "apex_ram_hw_v2";
    private static final String KEY_PHYS_KB = "physical_total_kb";
    private static final String KEY_USABLE_KB = "usable_total_kb";
    private static final String KEY_SCANNED = "hw_scanned_at";

    private static final int[] MARKET_GIB = {4, 6, 8, 12, 16, 18, 24, 32};

    private static final Pattern MEM_LINE =
            Pattern.compile("^(\\w+):\\s*(\\d+)\\s*kB", Pattern.CASE_INSENSITIVE);

    public long totalKb;
    public long availKb;
    public long usedKb;
    public int freePct;
    public int usedPct;
    public double totalGb;
    public double freeGb;
    public double usedGb;
    public boolean lowMemory;
    public int runningProcesses;
    public double storageAvailGb;
    public double storageUsedPct;
    public String source = "";
    public long physicalTotalKb;
    public long usableTotalKb;
    public boolean hwCached;

    private RamMetrics() {}

    public static RamMetrics sample(Context context) {
        Context app = context != null ? context.getApplicationContext() : null;

        long physicalKb = ensurePhysicalTotal(app);
        long usableKb = ensureUsableTotal(app);

        ProcMem proc = medianProcMem();
        AmMem am = readActivityManager(app);

        long totalKb = physicalKb > 0 ? physicalKb : 0;
        if (totalKb <= 0 && usableKb > 0) totalKb = usableKb;
        if (totalKb <= 0 && proc.totalKb > 0) totalKb = mapToMarketedKb(proc.totalKb);
        if (totalKb <= 0 && am.totalKb > 0) totalKb = mapToMarketedKb(am.totalKb);
        if (totalKb <= 0) totalKb = 1;

        long availKb;
        String source;
        if (proc.availKb > 0 && am.availKb > 0) {
            long delta = Math.abs(proc.availKb - am.availKb);
            if (totalKb > 0 && delta * 100 / totalKb <= 3) {
                availKb = (proc.availKb + am.availKb) / 2;
                source = "DeviceCare:MemAvailable~AM";
            } else {
                availKb = Math.max(proc.availKb, am.availKb);
                source = availKb == proc.availKb
                        ? "DeviceCare:MemAvailable"
                        : "DeviceCare:ActivityManager";
            }
        } else if (proc.availKb > 0) {
            availKb = proc.availKb;
            source = "DeviceCare:MemAvailable";
        } else if (am.availKb > 0) {
            availKb = am.availKb;
            source = "DeviceCare:ActivityManager";
        } else if (proc.reclaimableKb > 0) {
            availKb = proc.reclaimableKb;
            source = "DeviceCare:reclaimable";
        } else {
            availKb = 0;
            source = "DeviceCare:none";
        }

        if (availKb < 0) availKb = 0;
        if (availKb > totalKb) availKb = totalKb;

        long usedKb = totalKb - availKb;

        RamMetrics r = new RamMetrics();
        r.physicalTotalKb = physicalKb;
        r.usableTotalKb = usableKb > 0 ? usableKb : (proc.totalKb > 0 ? proc.totalKb : am.totalKb);
        r.hwCached = physicalKb > 0;
        r.totalKb = totalKb;
        r.availKb = availKb;
        r.usedKb = usedKb;
        r.freePct = clampPct((int) Math.round(100.0 * (double) availKb / (double) totalKb));
        r.usedPct = clampPct(100 - r.freePct);
        r.totalGb = kbToGb(totalKb);
        r.freeGb = kbToGb(availKb);
        r.usedGb = kbToGb(usedKb);
        r.lowMemory = am.lowMemory;
        r.runningProcesses = am.runningProcesses;
        r.source = source + "|marketed";
        fillStorage(r);
        return r;
    }

    private static long ensurePhysicalTotal(Context app) {
        if (app == null) {
            long u = readProcMemOnce().totalKb;
            return u > 0 ? mapToMarketedKb(u) : 0;
        }
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long cached = sp.getLong(KEY_PHYS_KB, 0L);
        long now = System.currentTimeMillis();
        long scannedAt = sp.getLong(KEY_SCANNED, 0L);
        if (cached > 1024L * 1024L && (now - scannedAt) < 24L * 3600 * 1000) {
            return cached;
        }
        long usable = scanUsableRamKb(app);
        long marketed = mapToMarketedKb(usable);
        if (marketed > 1024L * 512L) {
            sp.edit()
                    .putLong(KEY_PHYS_KB, marketed)
                    .putLong(KEY_USABLE_KB, usable)
                    .putLong(KEY_SCANNED, now)
                    .apply();
            Log.i(TAG, "HW RAM scan: usable=" + usable + " kB ("
                    + String.format(Locale.US, "%.2f GiB", kbToGb(usable))
                    + ") -> marketed=" + marketed + " kB ("
                    + String.format(Locale.US, "%.0f GB", kbToGb(marketed)) + ")"
                    + " sdk=" + Build.VERSION.SDK_INT);
            return marketed;
        }
        return cached > 0 ? cached : marketed;
    }

    private static long ensureUsableTotal(Context app) {
        if (app == null) return readProcMemOnce().totalKb;
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long cached = sp.getLong(KEY_USABLE_KB, 0L);
        if (cached > 0) return cached;
        return scanUsableRamKb(app);
    }

    private static long scanUsableRamKb(Context app) {
        List<Long> candidates = new ArrayList<>();
        ProcMem p = readProcMemOnce();
        if (p.totalKb > 0) candidates.add(p.totalKb);
        AmMem am = readActivityManager(app);
        if (am.totalKb > 0) candidates.add(am.totalKb);
        try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        ProcMem p2 = readProcMemOnce();
        if (p2.totalKb > 0) candidates.add(p2.totalKb);
        if (candidates.isEmpty()) return 0;
        Collections.sort(candidates);
        long max = candidates.get(candidates.size() - 1);
        long med = candidates.get(candidates.size() / 2);
        if (max > med && (max - med) * 100L / max <= 5) return max;
        return med;
    }

    /** Map kernel usable RAM to OEM marketed capacity. 10.9 GiB -> 12 GB. */
    static long mapToMarketedKb(long usableKb) {
        if (usableKb <= 0) return 0;
        double usableGib = usableKb / (1024.0 * 1024.0);
        for (int gib : MARKET_GIB) {
            if (Math.abs(usableGib - gib) / gib <= 0.03) {
                return gib * 1024L * 1024L;
            }
        }
        for (int gib : MARKET_GIB) {
            double tier = gib;
            if (usableGib < tier && usableGib >= tier * 0.85) {
                return gib * 1024L * 1024L;
            }
        }
        int best = MARKET_GIB[0];
        double bestDist = Math.abs(usableGib - best);
        for (int gib : MARKET_GIB) {
            double d = Math.abs(usableGib - gib);
            if (d < bestDist) {
                bestDist = d;
                best = gib;
            }
        }
        if (bestDist / Math.max(usableGib, 1) <= 0.20) {
            return best * 1024L * 1024L;
        }
        return usableKb;
    }

    private static ProcMem medianProcMem() {
        List<Long> avails = new ArrayList<>();
        ProcMem last = new ProcMem();
        for (int i = 0; i < 7; i++) {
            ProcMem m = readProcMemOnce();
            if (m.totalKb > 0) last.totalKb = m.totalKb;
            if (m.availKb > 0) avails.add(m.availKb);
            last.reclaimableKb = m.reclaimableKb;
            last.memFreeKb = m.memFreeKb;
            try { Thread.sleep(18); } catch (InterruptedException ignored) {}
        }
        if (!avails.isEmpty()) {
            Collections.sort(avails);
            last.availKb = avails.get(avails.size() / 2);
        }
        return last;
    }

    private static ProcMem readProcMemOnce() {
        ProcMem m = new ProcMem();
        long free = 0, buffers = 0, cached = 0, srec = 0, shmem = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher mt = MEM_LINE.matcher(line.trim());
                if (!mt.find()) continue;
                String key = mt.group(1);
                long val = Long.parseLong(mt.group(2));
                switch (key) {
                    case "MemTotal":
                        m.totalKb = val;
                        break;
                    case "MemAvailable":
                        m.availKb = val;
                        break;
                    case "MemFree":
                        free = val;
                        m.memFreeKb = val;
                        break;
                    case "Buffers":
                        buffers = val;
                        break;
                    case "Cached":
                        cached = val;
                        break;
                    case "SReclaimable":
                        srec = val;
                        break;
                    case "Shmem":
                        shmem = val;
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "meminfo", e);
        }
        long reclaim = free + buffers + cached + srec;
        if (shmem > 0 && reclaim > shmem) reclaim -= shmem;
        m.reclaimableKb = Math.max(0, reclaim);
        if (m.availKb <= 0) m.availKb = m.reclaimableKb;
        return m;
    }

    private static AmMem readActivityManager(Context app) {
        AmMem am = new AmMem();
        if (app == null) return am;
        try {
            ActivityManager mgr = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (mgr == null) return am;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            mgr.getMemoryInfo(info);
            am.totalKb = info.totalMem > 0 ? info.totalMem / 1024L : 0;
            am.availKb = info.availMem > 0 ? info.availMem / 1024L : 0;
            am.lowMemory = info.lowMemory;
            if (Build.VERSION.SDK_INT >= 16) {
                List<ActivityManager.RunningAppProcessInfo> procs = mgr.getRunningAppProcesses();
                am.runningProcesses = procs != null ? procs.size() : 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "AM", e);
        }
        return am;
    }

    private static void fillStorage(RamMetrics r) {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            r.storageAvailGb = avail / (1024.0 * 1024.0 * 1024.0);
            r.storageUsedPct = total > 0 ? ((total - avail) * 100.0) / total : 0;
        } catch (Exception ignored) {}
    }

    private static double kbToGb(long kb) {
        return kb / (1024.0 * 1024.0);
    }

    private static int clampPct(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }

    public JSONObject toJson(boolean hasRoot) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("pctFree", freePct);
            o.put("pctUsed", usedPct);
            o.put("bytesTotal", totalKb * 1024L);
            o.put("bytesFree", availKb * 1024L);
            o.put("bytesUsed", usedKb * 1024L);
            o.put("totalRamGb", totalGb);
            o.put("freeRamGb", freeGb);
            o.put("usedRamGb", usedGb);
            o.put("freeRamPct", freePct);
            o.put("usedRamPct", usedPct);
            o.put("availablePct", freePct);
            o.put("availableGb", freeGb);
            o.put("inUsePct", usedPct);
            o.put("inUseGb", usedGb);
            o.put("totalRamKb", totalKb);
            o.put("freeRamKb", availKb);
            o.put("usedRamKb", usedKb);
            o.put("physicalTotalKb", physicalTotalKb);
            o.put("usableTotalKb", usableTotalKb);
            o.put("hwCached", hwCached);
            o.put("lowMemory", lowMemory);
            o.put("hasRoot", hasRoot);
            o.put("runningProcesses", runningProcesses);
            o.put("storageAvailGb", storageAvailGb);
            o.put("storageUsedPct", storageUsedPct);
            o.put("memSource", source);
            o.put("model", "device_care_marketed");
        } catch (Exception ignored) {}
        return o;
    }

    private static final class ProcMem {
        long totalKb;
        long availKb;
        long reclaimableKb;
        long memFreeKb;
    }

    private static final class AmMem {
        long totalKb;
        long availKb;
        boolean lowMemory;
        int runningProcesses;
    }
}
