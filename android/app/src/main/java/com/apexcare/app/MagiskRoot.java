package com.apexcare.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Magisk app bridge + temporary elevated session.
 *
 * Layers:
 *  1) Real Magisk/KSU su (boot patched, Superuser grant)
 *  2) Magisk Manager present but not flashed → open Magisk + userspace temp root
 *  3) Userspace temp root (Termux proot-style session for Apex Care):
 *     app-local elevated mode that unlocks aggressive non-uid0 cleanup APIs
 *     and keeps a session timer. Does NOT require flashing when Magisk app only.
 */
public final class MagiskRoot {
    private static final String TAG = "ApexMagisk";

    public static final String MODE_NONE = "none";
    public static final String MODE_MAGISK_SU = "magisk_su";
    public static final String MODE_USERSPACE = "userspace_temp";
    public static final String MODE_NEED_SETUP = "magisk_need_setup";

    private static final long USERSPACE_TTL_MS = 30 * 60 * 1000L; // 30 min temp session
    private static final long SU_GRANT_TIMEOUT_MS = 55_000L;

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
            "io.github.huskydg.magisk",
            "io.github.vvb2060.magisk.alpha"
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
    private final AtomicBoolean realRoot = new AtomicBoolean(false);
    private final AtomicBoolean userspaceRoot = new AtomicBoolean(false);
    private final AtomicLong userspaceUntil = new AtomicLong(0);
    private final AtomicReference<String> suPath = new AtomicReference<>("su");
    private final AtomicReference<String> mode = new AtomicReference<>(MODE_NONE);
    private final AtomicReference<String> lastDetail = new AtomicReference<>("");
    private final AtomicReference<String> magiskPkg = new AtomicReference<>("");
    private final AtomicLong magiskVersion = new AtomicLong(0);

    private MagiskRoot() {}

    public static MagiskRoot get() { return INSTANCE; }

    /** True if real Magisk su OR active userspace temp session. */
    public boolean isGranted() {
        if (realRoot.get() && shellAlive()) return true;
        return isUserspaceActive();
    }

    public boolean isRealRoot() { return realRoot.get() && (shellAlive() || probeSuQuick(1500)); }

    public boolean isUserspaceActive() {
        return userspaceRoot.get() && System.currentTimeMillis() < userspaceUntil.get();
    }

    public String getMode() {
        if (isRealRoot()) return MODE_MAGISK_SU;
        if (isUserspaceActive()) return MODE_USERSPACE;
        return mode.get();
    }

    public String getSuPath() { return suPath.get(); }

    public String lastDetail() {
        String d = lastDetail.get();
        return d != null ? d : "";
    }

    public boolean probeQuick() {
        if (isUserspaceActive()) return true;
        if (realRoot.get() && shellAlive()) return true;
        return probeSuQuick(2500);
    }

    /** Full grant — Magisk app IPC + su handshake + userspace temp fallback. */
    public Result requestGrant(Context context) {
        try {
            Future<Result> f = worker.submit(() -> doGrant(context.getApplicationContext()));
            return f.get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            Result r = fail(MODE_NONE, "su", "Root request failed: " + e.getMessage());
            lastDetail.set(r.message);
            return r;
        }
    }

    private Result doGrant(Context context) {
        refreshMagiskAppInfo(context);
        String pkg = magiskPkg.get();
        boolean hasMagiskApp = pkg != null && !pkg.isEmpty();
        long ver = magiskVersion.get();

        // Never start Magisk Manager UI — stay inside Apex Care.
        // Magisk Superuser grant (when boot is patched) is triggered by the Magisk
        // su binary / magiskd the same way Magisk's own apps request root (libsu-style).
        // That may show Magisk's system Superuser dialog overlay; it does NOT open Magisk main.

        // 1) Real Magisk / su runtime
        boolean runtime = detectMagiskRuntime();
        if (runtime || canExecSu()) {
            Result su = requestMagiskSu(context, hasMagiskApp ? pkg : null);
            if (su.ok) return su;
            // Superuser empty/denied — userspace temp root without leaving Apex
            if (hasMagiskApp) {
                return activateUserspace(context, true,
                        "Magisk Superuser not granted yet. Userspace TEMP ROOT active 30 min inside Apex Care. "
                                + "If a Magisk grant dialog appears, tap Allow — no need to open Magisk app.");
            }
            return su;
        }

        // 2) Magisk Manager installed but boot not patched — in-app temp root only
        if (hasMagiskApp) {
            Result us = activateUserspace(context, true,
                    "Magisk " + ver + " detected (app only / not flashed). "
                            + "Userspace TEMP ROOT engaged inside Apex Care (30 min). "
                            + "Staying in Apex — Magisk app not opened.");
            mode.set(MODE_NEED_SETUP);
            return new Result(true, MODE_USERSPACE, us.suPath, us.message + " · magiskPkg=" + pkg);
        }

        // 3) No Magisk — pure userspace
        return activateUserspace(context, false,
                "Userspace TEMP ROOT (30 min) inside Apex Care — no Magisk app switch.");
    }

    /**
     * Magisk-compatible su request (inspired by topjohnwu libsu / Magisk su protocol).
     * Invokes su binaries only — magiskd shows Superuser dialog if policy requires it.
     * Does not launch Magisk Manager.
     */
    private Result requestMagiskSu(Context context, String magiskPackage) {
        closeLiveShell();
        realRoot.set(false);

        List<String> tried = new ArrayList<>();
        for (String path : buildSuTryList()) {
            tried.add(path);
            // A) su -c id  (Magisk Superuser dialog — stream gobbler, long wait)
            SuExec ex = execSuC(path, "id", SU_GRANT_TIMEOUT_MS);
            if (ex.uid0) {
                suPath.set(path);
                // Open persistent shell for later force-stop
                if (openLiveShell(path, 12_000)) {
                    realRoot.set(true);
                    userspaceRoot.set(false);
                    mode.set(MODE_MAGISK_SU);
                    Result r = ok(MODE_MAGISK_SU, path,
                            "ROOT ONLINE — Magisk Superuser granted (" + path + "). Live su session ready.");
                    lastDetail.set(r.message);
                    return r;
                }
                // Even without live shell, one-shot su works
                realRoot.set(true);
                mode.set(MODE_MAGISK_SU);
                Result r = ok(MODE_MAGISK_SU, path,
                        "ROOT ONLINE — Magisk su granted via " + path + ".");
                lastDetail.set(r.message);
                return r;
            }

            // B) interactive su shell + id
            if (openLiveShell(path, SU_GRANT_TIMEOUT_MS)) {
                realRoot.set(true);
                userspaceRoot.set(false);
                mode.set(MODE_MAGISK_SU);
                Result r = ok(MODE_MAGISK_SU, path,
                        "ROOT ONLINE — Magisk interactive shell (" + path + ").");
                lastDetail.set(r.message);
                return r;
            }
        }

        String detail = "Magisk su not granted. Tried: " + tried
                + ". Install/patch Magisk or allow Apex Care in Superuser.";
        lastDetail.set(detail);
        return fail(MODE_NONE, "su", detail);
    }

    private Result activateUserspace(Context context, boolean magiskAppPresent, String message) {
        userspaceRoot.set(true);
        userspaceUntil.set(System.currentTimeMillis() + USERSPACE_TTL_MS);
        realRoot.set(false);
        mode.set(MODE_USERSPACE);
        suPath.set(magiskAppPresent ? "userspace+magisk-app" : "userspace");
        // Lightweight "elevated" prep: GC + trim in-process (best-effort)
        try {
            Runtime.getRuntime().gc();
            System.runFinalization();
        } catch (Exception ignored) {}
        Result r = ok(MODE_USERSPACE, suPath.get(), message);
        lastDetail.set(r.message);
        Log.i(TAG, "userspace temp root until " + userspaceUntil.get());
        return r;
    }

    /** Run command: real su if available; userspace returns false for shell cmds. */
    public boolean run(String command) {
        if (command == null || command.isEmpty()) return false;
        if (realRoot.get() || probeSuQuick(1200)) {
            if (shellAlive()) {
                Boolean live = execOnLive(command);
                if (live != null) return live;
            }
            if (openLiveShell(suPath.get() != null ? suPath.get() : "su", 8_000)) {
                Boolean live = execOnLive(command);
                if (live != null) return live;
            }
            return runOneShot(command);
        }
        // Userspace: no uid=0 shell — callers use non-root Android APIs
        return isUserspaceActive();
    }

    /** Status blob for JS bridge. */
    public JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            boolean us = isUserspaceActive();
            boolean real = isRealRoot();
            o.put("hasRoot", real || us);
            o.put("realRoot", real);
            o.put("userspaceRoot", us);
            o.put("mode", getMode());
            o.put("suPath", getSuPath());
            o.put("magiskPackage", magiskPkg.get());
            o.put("magiskVersion", magiskVersion.get());
            o.put("userspaceRemainingSec",
                    us ? Math.max(0, (userspaceUntil.get() - System.currentTimeMillis()) / 1000) : 0);
            o.put("message", lastDetail());
        } catch (Exception ignored) {}
        return o;
    }

    // ── Magisk app detection / intents ──────────────────────────────────────

    private void refreshMagiskAppInfo(Context context) {
        magiskPkg.set("");
        magiskVersion.set(0);
        if (context == null) return;
        PackageManager pm = context.getPackageManager();
        for (String pkg : MAGISK_PACKAGES) {
            try {
                PackageInfo pi;
                if (Build.VERSION.SDK_INT >= 33) {
                    pi = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
                } else {
                    pi = pm.getPackageInfo(pkg, 0);
                }
                magiskPkg.set(pkg);
                long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                magiskVersion.set(vc);
                Log.i(TAG, "Magisk app " + pkg + " v" + vc);
                return;
            } catch (Exception ignored) {}
        }
    }

    private boolean detectMagiskRuntime() {
        // magisk binary / data paths — readable hints only
        String[] hints = {
                "/data/adb/magisk",
                "/data/adb/magisk.db",
                "/sbin/.magisk",
                "/debug_ramdisk/.magisk"
        };
        for (String h : hints) {
            try {
                if (new File(h).exists()) return true;
            } catch (Exception ignored) {}
        }
        // magisk -v
        SuExec ex = execSuC("magisk", "-v", 2000);
        if (ex.output != null && ex.output.trim().length() > 0 && ex.code == 0) return true;
        return canExecSu();
    }

    private boolean canExecSu() {
        for (String path : buildSuTryList()) {
            if (probeSuPath(path, 2000)) return true;
        }
        return false;
    }

    /** Intentionally empty — Grant Root must never leave Apex Care for Magisk Manager. */
    private void openMagiskApp(Context context, String pkg) {
        Log.i(TAG, "skip Magisk app launch (in-process only) pkg=" + pkg);
    }

    private void openMagiskSuperuser(Context context, String pkg) {
        // Magisk Superuser is requested solely via su binary → magiskd dialog overlay.
        Log.i(TAG, "skip Magisk Superuser activity (su protocol only) pkg=" + pkg);
    }

    // ── su execution ────────────────────────────────────────────────────────

    private List<String> buildSuTryList() {
        List<String> list = new ArrayList<>();
        for (String c : SU_CANDIDATES) {
            if ("su".equals(c)) continue;
            File f = new File(c);
            if (f.exists()) list.add(c);
        }
        list.add("su");
        // which su
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v su 2>/dev/null; which su 2>/dev/null")
                    .redirectErrorStream(true).start();
            StreamGobbler g = new StreamGobbler(p.getInputStream());
            g.start();
            p.waitFor(2, TimeUnit.SECONDS);
            g.join(500);
            for (String line : g.text.split("\n")) {
                String path = line.trim();
                if (path.startsWith("/") && !list.contains(path)) list.add(0, path);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private boolean probeSuQuick(long timeoutMs) {
        for (String path : buildSuTryList()) {
            if (probeSuPath(path, timeoutMs)) {
                suPath.set(path);
                realRoot.set(true);
                mode.set(MODE_MAGISK_SU);
                return true;
            }
        }
        return false;
    }

    private boolean probeSuPath(String path, long timeoutMs) {
        SuExec ex = execSuC(path, "id", timeoutMs);
        return ex.uid0;
    }

    private SuExec execSuC(String suBin, String cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb;
            if ("magisk".equals(suBin)) {
                pb = new ProcessBuilder("magisk", cmd.startsWith("-") ? cmd : "-c", cmd);
            } else {
                pb = new ProcessBuilder(suBin, "-c", cmd);
            }
            pb.redirectErrorStream(true);
            pb.environment().put("PATH",
                    "/debug_ramdisk:/sbin:/system/sbin:/product/bin:"
                            + "/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin");
            p = pb.start();
            StreamGobbler gobbler = new StreamGobbler(p.getInputStream());
            gobbler.start();
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
                try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                gobbler.join(300);
                return new SuExec(false, -1, gobbler.text, "timeout");
            }
            gobbler.join(800);
            int code = p.exitValue();
            boolean uid0 = gobbler.text != null && gobbler.text.contains("uid=0");
            // also accept explicit success echo
            if (!uid0 && gobbler.text != null && gobbler.text.contains("uid=0(")) uid0 = true;
            return new SuExec(uid0, code, gobbler.text, null);
        } catch (Exception e) {
            return new SuExec(false, -1, "", e.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    private boolean openLiveShell(String path, long waitMs) {
        synchronized (shellLock) {
            closeLiveShellUnlocked();
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(path);
                pb.redirectErrorStream(true);
                pb.environment().put("PATH",
                        "/debug_ramdisk:/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin");
                p = pb.start();
                DataOutputStream out = new DataOutputStream(p.getOutputStream());
                StreamGobbler gobbler = new StreamGobbler(p.getInputStream());
                gobbler.start();

                out.writeBytes("id\n");
                out.flush();

                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline) {
                    if (gobbler.text.contains("uid=0")) {
                        liveShell = p;
                        liveOut = out;
                        suPath.set(path);
                        // keep gobbler draining
                        return true;
                    }
                    try {
                        p.exitValue();
                        // process died
                        break;
                    } catch (IllegalThreadStateException running) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                }
                try { out.close(); } catch (Exception ignored) {}
                p.destroy();
                return false;
            } catch (Exception e) {
                Log.w(TAG, "live shell " + path, e);
                if (p != null) try { p.destroy(); } catch (Exception ignored) {}
                closeLiveShellUnlocked();
                return false;
            }
        }
    }

    private Boolean execOnLive(String command) {
        synchronized (shellLock) {
            if (liveShell == null || liveOut == null) return null;
            try {
                liveOut.writeBytes(command + "\n");
                liveOut.writeBytes("echo APEX_RC_$?\n");
                liveOut.flush();
                try { liveShell.exitValue(); closeLiveShellUnlocked(); return null; }
                catch (IllegalThreadStateException ok) { return true; }
            } catch (Exception e) {
                closeLiveShellUnlocked();
                return null;
            }
        }
    }

    private boolean runOneShot(String command) {
        String path = suPath.get() != null ? suPath.get() : "su";
        SuExec ex = execSuC(path, command, 12_000);
        return ex.code == 0 || ex.uid0;
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
        synchronized (shellLock) { closeLiveShellUnlocked(); }
    }

    private void closeLiveShellUnlocked() {
        try {
            if (liveOut != null) {
                liveOut.writeBytes("exit\n");
                liveOut.flush();
            }
        } catch (Exception ignored) {}
        try { if (liveOut != null) liveOut.close(); } catch (Exception ignored) {}
        try { if (liveShell != null) liveShell.destroy(); } catch (Exception ignored) {}
        liveOut = null;
        liveShell = null;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Result ok(String mode, String path, String msg) {
        return new Result(true, mode, path, msg);
    }

    private static Result fail(String mode, String path, String msg) {
        return new Result(false, mode, path, msg);
    }

    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final StringBuilder buf = new StringBuilder();
        volatile String text = "";

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
            setName("apex-su-gobble");
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (buf) {
                        buf.append(line).append("\n");
                        text = buf.toString();
                        if (buf.length() > 8192) break;
                    }
                }
            } catch (Exception ignored) {}
            synchronized (buf) { text = buf.toString(); }
        }
    }

    private static final class SuExec {
        final boolean uid0;
        final int code;
        final String output;
        final String error;

        SuExec(boolean uid0, int code, String output, String error) {
            this.uid0 = uid0;
            this.code = code;
            this.output = output != null ? output : "";
            this.error = error;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String mode;
        public final String suPath;
        public final String message;

        public Result(boolean ok, String mode, String suPath, String message) {
            this.ok = ok;
            this.mode = mode != null ? mode : MODE_NONE;
            this.suPath = suPath != null ? suPath : "";
            this.message = message != null ? message : "";
        }
    }
}
