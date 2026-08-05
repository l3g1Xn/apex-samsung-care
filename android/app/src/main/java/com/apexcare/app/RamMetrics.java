package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accurate free/used RAM for Apex Care + widget.
 *
 * free = memory available to apps (MemAvailable / ActivityManager.availMem)
 * used = total - free
 * freePct + usedPct = 100 (never swapped)
 *
 * Samsung Device Care "used" is typically total − available; we match that model.
 */
public final class RamMetrics {
    private static final String TAG = "ApexRam";
    private static final Pattern KV = Pattern.compile("^([A-Za-z0-9_()]+):\\s+(\\d+)\\s+kB");

    public long totalKb;
    public long availKb;   // free / available to apps
    public long usedKb;
    public long memFreeKb;
    public long buffersKb;
    public long cachedKb;
    public long sreclaimKb;
    public int freePct;    // 0–100, share of total that is free
    public int usedPct;    // 0–100, share of total that is used
    public double totalGb;
    public double freeGb;
    public double usedGb;
    public boolean lowMemory;
    public int runningProcesses;
    public double storageAvailGb;
    public double storageUsedPct;
    public String source = "";

    private RamMetrics() {}

    public static RamMetrics sample(Context context) {
        // Multi-sample MemAvailable (median) + ActivityManager cross-check
        long totalKb = 0;
        List<Long> availSamples = new ArrayList<>();
        long memFree = 0, buffers = 0, cached = 0, srec = 0;

        for (int i = 0; i < 5; i++) {
            Meminfo mi = readMeminfo();
            if (mi.totalKb > 0) totalKb = mi.totalKb;
            if (mi.availKb > 0) availSamples.add(mi.availKb);
            memFree = mi.memFreeKb;
            buffers = mi.buffersKb;
            cached = mi.cachedKb;
            srec = mi.sreclaimKb;
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        }

        long procAvail = 0;
        if (!availSamples.isEmpty()) {
            Collections.sort(availSamples);
            procAvail = availSamples.get(availSamples.size() / 2);
        }

        // Classic reclaimable estimate if MemAvailable missing
        long reclaimable = memFree + buffers + cached + srec;

        long amTotalKb = 0, amAvailKb = 0;
        boolean low = false;
        int procs = 0;
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(info);
                    amTotalKb = info.totalMem > 0 ? info.totalMem / 1024L : 0;
                    amAvailKb = info.availMem > 0 ? info.availMem / 1024L : 0;
                    low = info.lowMemory;
                    List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                    procs = list != null ? list.size() : 0;
                }
            } catch (Exception e) {
                Log.w(TAG, "ActivityManager", e);
            }
        }

        if (totalKb <= 0) totalKb = amTotalKb;

        // free/available selection (never use "used" here):
        // 1) MemAvailable (kernel)  2) ActivityManager.availMem  3) free+buffers+cached+SReclaimable
        // On One UI, AM.availMem ≈ MemAvailable. Take the higher of the two primary sources
        // so free% is not under-reported (looks like used% if inverted/low).
        long availKb = 0;
        String source = "none";
        if (procAvail > 0 && amAvailKb > 0) {
            availKb = Math.max(procAvail, amAvailKb);
            source = (availKb == procAvail) ? "MemAvailable" : "ActivityManager";
            if (Math.abs(procAvail - amAvailKb) < totalKb / 50) {
                source = "MemAvailable≈AM";
            }
        } else if (procAvail > 0) {
            availKb = procAvail;
            source = "MemAvailable";
        } else if (amAvailKb > 0) {
            availKb = amAvailKb;
            source = "ActivityManager";
        } else if (reclaimable > 0) {
            availKb = reclaimable;
            source = "reclaimable";
        }

        if (totalKb <= 0) totalKb = 1;
        if (availKb < 0) availKb = 0;
        if (availKb > totalKb) availKb = totalKb;

        long usedKb = totalKb - availKb;

        RamMetrics r = new RamMetrics();
        r.totalKb = totalKb;
        r.availKb = availKb;
        r.usedKb = usedKb;
        r.memFreeKb = memFree;
        r.buffersKb = buffers;
        r.cachedKb = cached;
        r.sreclaimKb = srec;
        // Explicit free vs used — never inverted
        r.freePct = (int) Math.round(100.0 * availKb / (double) totalKb);
        r.usedPct = (int) Math.round(100.0 * usedKb / (double) totalKb);
        if (r.freePct + r.usedPct != 100) {
            r.usedPct = Math.max(0, Math.min(100, 100 - r.freePct));
        }
        r.freePct = Math.max(0, Math.min(100, r.freePct));
        r.usedPct = Math.max(0, Math.min(100, r.usedPct));
        r.totalGb = totalKb / (1024.0 * 1024.0);
        r.freeGb = availKb / (1024.0 * 1024.0);
        r.usedGb = usedKb / (1024.0 * 1024.0);
        r.lowMemory = low;
        r.runningProcesses = procs;
        r.source = source;

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            r.storageAvailGb = avail / (1024.0 * 1024.0 * 1024.0);
            r.storageUsedPct = total > 0 ? ((total - avail) * 100.0) / total : 0;
        } catch (Exception ignored) {}

        return r;
    }

    public JSONObject toJson(boolean hasRoot) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            // free = available to apps; used = total − free (Samsung Device Care model)
            o.put("freeRamPct", freePct);
            o.put("usedRamPct", usedPct);
            o.put("totalRamGb", totalGb);
            o.put("freeRamGb", freeGb);
            o.put("usedRamGb", usedGb);
            o.put("totalRamKb", totalKb);
            o.put("freeRamKb", availKb);
            o.put("usedRamKb", usedKb);
            o.put("memFreeKb", memFreeKb);
            o.put("lowMemory", lowMemory);
            o.put("hasRoot", hasRoot);
            o.put("runningProcesses", runningProcesses);
            o.put("storageAvailGb", storageAvailGb);
            o.put("storageUsedPct", storageUsedPct);
            o.put("memSource", source);
            // aliases so UI never confuses free vs used
            o.put("availablePct", freePct);
            o.put("availableGb", freeGb);
            o.put("inUsePct", usedPct);
            o.put("inUseGb", usedGb);
        } catch (Exception ignored) {}
        return o;
    }

    private static final class Meminfo {
        long totalKb, availKb, memFreeKb, buffersKb, cachedKb, sreclaimKb;
    }

    private static Meminfo readMeminfo() {
        Meminfo m = new Meminfo();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher match = KV.matcher(line);
                if (!match.matches()) continue;
                String key = match.group(1);
                long val = Long.parseLong(match.group(2));
                switch (key) {
                    case "MemTotal":
                        m.totalKb = val;
                        break;
                    case "MemAvailable":
                        m.availKb = val;
                        break;
                    case "MemFree":
                        m.memFreeKb = val;
                        break;
                    case "Buffers":
                        m.buffersKb = val;
                        break;
                    case "Cached":
                        m.cachedKb = val;
                        break;
                    case "SReclaimable":
                        m.sreclaimKb = val;
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "meminfo", e);
        }
        // If kernel has no MemAvailable, approximate
        if (m.availKb <= 0) {
            m.availKb = m.memFreeKb + m.buffersKb + m.cachedKb + m.sreclaimKb;
        }
        return m;
    }
}
