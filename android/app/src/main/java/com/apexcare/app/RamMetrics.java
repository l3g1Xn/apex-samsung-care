package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
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
 * Device Care–style RAM scanner for Apex Care.
 *
 * Reverse-engineered model matching Samsung Device Care / Smart Manager:
 *   total     = physical RAM (cached from hardware scan on first run)
 *   available = kernel MemAvailable (median) preferred, else ActivityManager.availMem
 *   used      = total − available
 *   freePct   = round(100 × available / total)   // NEVER used/total
 *   usedPct   = 100 − freePct
 *
 * Samsung Device Care (One UI) displays Used GB of Total and Available the same way:
 * {@code used = totalMem - availMem} from ActivityManager + /proc/meminfo alignment.
 */
public final class RamMetrics {
    private static final String TAG = "ApexRam";
    private static final String PREFS = "apex_ram_hw";
    private static final String KEY_PHYS_KB = "physical_total_kb";
    private static final String KEY_SCANNED = "hw_scanned_at";

    /** Meminfo lines: "MemTotal:  12345678 kB" (flexible whitespace). */
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
    public boolean hwCached;

    private RamMetrics() {}

    public static RamMetrics sample(Context context) {
        Context app = context != null ? context.getApplicationContext() : null;

        // ── 1) Hardware total RAM scan (cached after install/first open) ──
        long physicalKb = ensurePhysicalTotal(app);

        // ── 2) Live available (Device Care uses avail ≈ MemAvailable / AM.availMem) ──
        ProcMem proc = medianProcMem();
        AmMem am = readActivityManager(app);

        long totalKb = physicalKb > 0 ? physicalKb : 0;
        if (totalKb <= 0 && proc.totalKb > 0) totalKb = proc.totalKb;
        if (totalKb <= 0 && am.totalKb > 0) totalKb = am.totalKb;
        if (totalKb <= 0) totalKb = 1;

        // Available: prefer MemAvailable (what modern Android / Device Care track for free)
        // Fall back to AM.availMem, then free+buffers+cached+SReclaimable.
        long availKb;
        String source;
        if (proc.availKb > 0 && am.availKb > 0) {
            // When both exist, prefer MemAvailable (kernel Device Care source).
            // If AM is within 3% of MemAvailable, average for stability.
            long delta = Math.abs(proc.availKb - am.availKb);
            if (delta * 100 / totalKb <= 3) {
                availKb = (proc.availKb + am.availKb) / 2;
                source = "DeviceCare:MemAvailable≈AM";
            } else {
                // Prefer the higher available — under-reporting free is the common bug
                // (makes free% look like used%). Device Care leans optimistic on free.
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

        // Device Care identity: used = total − available  (never inverted)
        long usedKb = totalKb - availKb;

        RamMetrics r = new RamMetrics();
        r.physicalTotalKb = physicalKb;
        r.hwCached = physicalKb > 0;
        r.totalKb = totalKb;
        r.availKb = availKb;
        r.usedKb = usedKb;
        // free% from available; used% complement — labels never swapped
        r.freePct = clampPct((int) Math.round(100.0 * (double) availKb / (double) totalKb));
        r.usedPct = clampPct(100 - r.freePct);
        r.totalGb = kbToGb(totalKb);
        r.freeGb = kbToGb(availKb);
        r.usedGb = kbToGb(usedKb);
        r.lowMemory = am.lowMemory;
        r.runningProcesses = am.runningProcesses;
        r.source = source;
        fillStorage(r);
        return r;
    }

    /**
     * One-time (and periodic refresh) hardware scan of physical RAM.
     * Uses /proc/meminfo MemTotal + ActivityManager.totalMem; caches the larger
     * stable reading so % free is against real device capacity (e.g. 12 GB).
     */
    private static long ensurePhysicalTotal(Context app) {
        if (app == null) return readProcMemOnce().totalKb;
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long cached = sp.getLong(KEY_PHYS_KB, 0L);
        long now = System.currentTimeMillis();
        long scannedAt = sp.getLong(KEY_SCANNED, 0L);
        // Re-scan every 7 days or if missing
        if (cached > 1024L * 1024L && (now - scannedAt) < 7L * 24 * 3600 * 1000) {
            return cached;
        }
        long scanned = scanPhysicalRamKb(app);
        if (scanned > 1024L * 512L) { // > 512 MB sanity
            sp.edit().putLong(KEY_PHYS_KB, scanned).putLong(KEY_SCANNED, now).apply();
            Log.i(TAG, "Hardware RAM scan cached: " + scanned + " kB ("
                    + String.format(Locale.US, "%.2f GB", kbToGb(scanned)) + ")");
            return scanned;
        }
        return cached > 0 ? cached : scanned;
    }

    private static long scanPhysicalRamKb(Context app) {
        List<Long> candidates = new ArrayList<>();
        ProcMem p = readProcMemOnce();
        if (p.totalKb > 0) candidates.add(p.totalKb);
        AmMem am = readActivityManager(app);
        if (am.totalKb > 0) candidates.add(am.totalKb);
        // Secondary sample (meminfo can jitter slightly on some OEMs)
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        ProcMem p2 = readProcMemOnce();
        if (p2.totalKb > 0) candidates.add(p2.totalKb);
        if (candidates.isEmpty()) return 0;
        Collections.sort(candidates);
        // Median total — physical capacity
        long med = candidates.get(candidates.size() / 2);
        // Prefer max if within 8% (advertised vs usable)
        long max = candidates.get(candidates.size() - 1);
        if (max > med && (max - med) * 100 / max <= 8) {
            return max;
        }
        return med;
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
        // Classic Linux available approx (Device Care fallback path)
        // reclaimable ≈ free + buffers + cached + sreclaim − shmem (shmem not always freeable)
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
            List<ActivityManager.RunningAppProcessInfo> procs = mgr.getRunningAppProcesses();
            am.runningProcesses = procs != null ? procs.size() : 0;
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
            // Canonical Device Care fields (unambiguous)
            o.put("pctFree", freePct);
            o.put("pctUsed", usedPct);
            o.put("bytesTotal", totalKb * 1024L);
            o.put("bytesFree", availKb * 1024L);
            o.put("bytesUsed", usedKb * 1024L);
            o.put("totalRamGb", totalGb);
            o.put("freeRamGb", freeGb);
            o.put("usedRamGb", usedGb);
            // Legacy keys — same semantics, never swapped
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
            o.put("hwCached", hwCached);
            o.put("lowMemory", lowMemory);
            o.put("hasRoot", hasRoot);
            o.put("runningProcesses", runningProcesses);
            o.put("storageAvailGb", storageAvailGb);
            o.put("storageUsedPct", storageUsedPct);
            o.put("memSource", source);
            o.put("model", "device_care");
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
