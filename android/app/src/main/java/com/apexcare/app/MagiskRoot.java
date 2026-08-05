package com.apexcare.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Magisk / KernelSU / APatch root helper.
 *
 * Magisk does not grant root on APK install. Grant Root runs the real su
 * handshake in a background worker so Magisk can show its SuperUser prompt;
 * once allowed, a live root shell is kept for force-stop / clean.
 */
public final class MagiskRoot {
    private static final String TAG = "ApexMagisk";

    /** Common su locations Magisk / KSU / APatch expose or intercept. */
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
            "/system_ext/bin/su",
            "/su/bin/su",
            "/data/local/tmp/su"
    };

    private static final String[] MAGISK_PACKAGES = {
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk"
    };

    private static final MagiskRoot INSTANCE = new MagiskRoot();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apex-magisk-root");
        t.setDaemon(true);
        return t;
    });

    private final Object shellLock = new Object();
    private Process liveShell;
    private DataOutputStream liveOut;
    private BufferedReader liveIn;
    private String suPath = "su";
    private final AtomicBoolean granted = new AtomicBoolean(false);
    private final AtomicReference<String> lastDetail = new AtomicReference<>("");

    private MagiskRoot() {}

    public static MagiskRoot get() { return INSTANCE; }

    public boolean isGranted() { return granted.get(); }

    public String getSuPath() { return suPath; }

    public String lastDetail() {
        String d = lastDetail.get();
        return d != null ? d : "";
    }

    /** Quick non-interactive probe (no long Magisk wait). */
    public boolean probeQuick() {
        if (granted.get() && shellAlive()) return true;
        String path = resolveSuBinary();
        if (path == null) return false;
        try {
            Process p = new ProcessBuilder(path, "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            String out = readAll(p.getInputStream(), 2500);
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && out != null && out.contains("uid=0");
            if (!ok) try { p.destroy(); } catch (Exception ignored) {}
            if (ok) {
                granted.set(true);
                suPath = path;
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Full Magisk grant flow (blocks worker up to ~55s for SuperUser dialog).
     * Called from Grant Temporary Root — runs off the UI thread via worker.
     */
    public Result requestGrant(Context context) {
        try {
            Future<Result> f = worker.submit(() -> doGrant(context));
            return f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Result r = new Result(false, "su", "Root request timed out or failed: " + e.getMessage());
            lastDetail.set(r.message);
            return r;
        }
    }

    private Result doGrant(Context context) {
        closeLiveShell();
        granted.set(false);

        String path = resolveSuBinary();
        if (path == null) {
            // Still try plain "su" — Magisk often injects it into PATH only at exec time
            path = "su";
        }
        suPath = path;

        // Hint Magisk manager so SuperUser policy is ready (best-effort, non-fatal)
        softOpenMagisk(context);

        // Primary Magisk workaround: interactive su shell (triggers grant UI every first deny cycle)
        Result interactive = openLiveShell(path, 50_000);
        if (interactive.ok) {
            granted.set(true);
            lastDetail.set(interactive.message);
            // Warm Magisk session with a no-op root cmd Magisk always allows once granted
            execOnLive("id");
            return interactive;
        }

        // Fallback one-shots Magisk still honors after user taps Grant
        String[] oneShots = {
                path,
                "su",
                "/system/bin/su",
                "/system/xbin/su"
        };
        for (String candidate : oneShots) {
            if (candidate == null) continue;
            try {
                Process p = new ProcessBuilder(candidate, "-c", "id")
                        .redirectErrorStream(true)
                        .start();
                String out = readAll(p.getInputStream(), 20_000);
                boolean ok = p.waitFor(25, TimeUnit.SECONDS)
                        && out != null && out.contains("uid=0");
                if (ok) {
                    suPath = candidate;
                    openLiveShell(candidate, 8_000);
                    granted.set(true);
                    Result r = new Result(true, candidate,
                            "ROOT ONLINE via Magisk/su (" + candidate + "). Force-stop unlocked.");
                    lastDetail.set(r.message);
                    return r;
                }
            } catch (Exception ignored) {}
        }

        Result fail = new Result(false, path,
                "Magisk did not grant root. In Magisk → Superuser, allow Apex Care, then tap Grant Temporary Root again.");
        lastDetail.set(fail.message);
        return fail;
    }

    /** Run a root command; prefers live Magisk shell. */
    public boolean run(String command) {
        if (command == null || command.isEmpty()) return false;
        if (shellAlive()) {
            Boolean ok = execOnLive(command);
            if (ok != null) return ok;
        }
        // Re-open shell once (Magisk auto-allows after permanent grant)
        if (openLiveShell(suPath != null ? suPath : "su", 8_000).ok) {
            Boolean ok = execOnLive(command);
            if (ok != null) return ok;
        }
        return runOneShot(command);
    }

    private boolean runOneShot(String command) {
        String path = suPath != null ? suPath : "su";
        Process p = null;
        DataOutputStream os = null;
        try {
            p = new ProcessBuilder(path).redirectErrorStream(true).start();
            os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            boolean finished = p.waitFor(12, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (p != null) p.destroy();
        }
    }

    private Result openLiveShell(String path, long waitMs) {
        synchronized (shellLock) {
            closeLiveShellUnlocked();
            try {
                ProcessBuilder pb = new ProcessBuilder(path);
                pb.redirectErrorStream(true);
                // Magisk-friendly environment
                pb.environment().put("PATH",
                        "/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:"
                                + "/system/bin:/system/xbin:/vendor/bin:/vendor/xbin");
                Process p = pb.start();
                DataOutputStream out = new DataOutputStream(p.getOutputStream());
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));

                // Magisk SuperUser handshake
                out.writeBytes("id\n");
                out.flush();

                long deadline = System.currentTimeMillis() + waitMs;
                StringBuilder buf = new StringBuilder();
                boolean uid0 = false;
                while (System.currentTimeMillis() < deadline) {
                    if (in.ready()) {
                        String line = in.readLine();
                        if (line == null) break;
                        buf.append(line).append('\n');
                        if (line.contains("uid=0")) {
                            uid0 = true;
                            break;
                        }
                    } else {
                        try { Thread.sleep(40); } catch (InterruptedException ignored) {}
                        // Process died without grant
                        try {
                            p.exitValue();
                            break;
                        } catch (IllegalThreadStateException stillRunning) {
                            // keep waiting for Magisk dialog
                        }
                    }
                }

                if (!uid0) {
                    try { p.destroy(); } catch (Exception ignored) {}
                    return new Result(false, path,
                            "Waiting for Magisk grant timed out via " + path
                                    + (buf.length() > 0 ? (" · " + buf.toString().trim()) : ""));
                }

                liveShell = p;
                liveOut = out;
                liveIn = in;
                suPath = path;
                return new Result(true, path,
                        "ROOT ONLINE — Magisk elevated session (" + path + "). Deeper am force-stop unlocked.");
            } catch (Exception e) {
                closeLiveShellUnlocked();
                return new Result(false, path, "su exec failed: " + e.getMessage());
            }
        }
    }

    private Boolean execOnLive(String command) {
        synchronized (shellLock) {
            if (liveShell == null || liveOut == null) return null;
            try {
                // Marker-based IO so Magisk shell stays open across commands
                liveOut.writeBytes(command + "\n");
                liveOut.writeBytes("echo APEX_RC_$?\n");
                liveOut.flush();
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    if (liveIn != null && liveIn.ready()) {
                        String line = liveIn.readLine();
                        if (line == null) {
                            closeLiveShellUnlocked();
                            return null;
                        }
                        if (line.startsWith("APEX_RC_")) {
                            try {
                                int code = Integer.parseInt(line.substring(8).trim());
                                return code == 0;
                            } catch (NumberFormatException nfe) {
                                return true;
                            }
                        }
                    } else {
                        try { Thread.sleep(15); } catch (InterruptedException ignored) {}
                        try {
                            liveShell.exitValue();
                            closeLiveShellUnlocked();
                            return null;
                        } catch (IllegalThreadStateException ok) { /* still up */ }
                    }
                }
                return true; // command issued; Magisk may not echo
            } catch (Exception e) {
                Log.w(TAG, "live exec", e);
                closeLiveShellUnlocked();
                return null;
            }
        }
    }

    private boolean shellAlive() {
        synchronized (shellLock) {
            if (liveShell == null) return false;
            try {
                liveShell.exitValue();
                closeLiveShellUnlocked();
                return false;
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }
    }

    public void closeLiveShell() {
        synchronized (shellLock) {
            closeLiveShellUnlocked();
        }
    }

    private void closeLiveShellUnlocked() {
        try { if (liveOut != null) { liveOut.writeBytes("exit\n"); liveOut.flush(); } } catch (Exception ignored) {}
        try { if (liveOut != null) liveOut.close(); } catch (Exception ignored) {}
        try { if (liveIn != null) liveIn.close(); } catch (Exception ignored) {}
        try { if (liveShell != null) liveShell.destroy(); } catch (Exception ignored) {}
        liveOut = null;
        liveIn = null;
        liveShell = null;
    }

    private String resolveSuBinary() {
        for (String c : SU_CANDIDATES) {
            if ("su".equals(c)) continue; // PATH su tried last via exec
            File f = new File(c);
            if (f.exists() && f.canExecute()) return c;
        }
        // which su
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v su || which su")
                    .redirectErrorStream(true).start();
            String out = readAll(p.getInputStream(), 1500);
            p.waitFor(2, TimeUnit.SECONDS);
            if (out != null) {
                String path = out.trim().split("\\s+")[0];
                if (path.startsWith("/") && new File(path).exists()) return path;
            }
        } catch (Exception ignored) {}
        return "su";
    }

    private void softOpenMagisk(Context context) {
        if (context == null) return;
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : MAGISK_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    // Do not steal focus with full launch — Magisk shows su dialog on top anyway.
                    // Touch package so Magisk policy daemon is warm.
                    Intent i = pm.getLaunchIntentForPackage(pkg);
                    if (i != null) {
                        // no startActivity — avoid leaving Apex Care
                        Log.i(TAG, "Magisk package present: " + pkg);
                    }
                    return;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(java.io.InputStream in, long maxWaitMs) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                if (br.ready()) {
                    String line = br.readLine();
                    if (line == null) break;
                    sb.append(line).append('\n');
                    if (sb.length() > 4096) break;
                } else {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    if (sb.length() > 0 && !br.ready()) break;
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    public static final class Result {
        public final boolean ok;
        public final String suPath;
        public final String message;

        public Result(boolean ok, String suPath, String message) {
            this.ok = ok;
            this.suPath = suPath;
            this.message = message;
        }
    }
}
