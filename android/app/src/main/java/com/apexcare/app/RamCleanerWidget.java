package com.apexcare.app;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RamCleanerWidget extends AppWidgetProvider {

    public static final String ACTION_CLEAN_RAM = "com.apexcare.app.ACTION_CLEAN_RAM";
    public static final String ACTION_REFRESH = "com.apexcare.app.ACTION_REFRESH";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, manager, id, null);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CLEAN_RAM.equals(action)) {
            setAllButtons(context, "…");
            HANDLER.postDelayed(() -> setAllButtons(context, "···"), 160);
            HANDLER.postDelayed(() -> {
                CleanResult r = runForceClean(context);
                refreshAll(context);
                setAllButtons(context, "Done");
                String msg = r.hasRoot
                        ? "Force-closed " + r.closed + " · +" + String.format(Locale.US, "%.1f", r.freedGb) + " GB free"
                        : "Closed " + r.closed + " bg · +" + String.format(Locale.US, "%.1f", r.freedGb) + " GB";
                Toast.makeText(context, "Apex Care · " + msg, Toast.LENGTH_SHORT).show();
            }, 400);
            HANDLER.postDelayed(() -> setAllButtons(context, context.getString(R.string.widget_clean)), 1700);
        } else if (ACTION_REFRESH.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            refreshAll(context);
        }
    }

    private static void setAllButtons(Context context, String label) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, RamCleanerWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_ram_cleaner);
            views.setTextViewText(R.id.widget_clean_btn, label);
            manager.partiallyUpdateAppWidget(id, views);
        }
    }

    private static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, RamCleanerWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        for (int id : ids) {
            updateWidget(context, manager, id, null);
        }
    }

    static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId, String btnOverride) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_ram_cleaner);

        RamMetrics ram = RamMetrics.sample(context);
        boolean rooted = hasRoot();
        views.setTextViewText(R.id.widget_title,
                rooted ? "Apex Care · ROOT" : "Apex Care");
        views.setTextViewText(R.id.widget_ram,
                String.format(Locale.US, "%d%% free", ram.freePct));
        views.setTextViewText(R.id.widget_storage,
                String.format(Locale.US, "Used %.1f / %.1f GB · Avail %.1f",
                        ram.usedGb, ram.totalGb, ram.freeGb));
        views.setTextViewText(R.id.widget_meta,
                String.format(Locale.US, "%d%% used · %d procs · disk %.1f free",
                        ram.usedPct, ram.runningProcesses, ram.storageAvailGb));

        views.setTextViewText(R.id.widget_clean_btn,
                btnOverride != null ? btnOverride : context.getString(R.string.widget_clean));

        Intent cleanIntent = new Intent(context, RamCleanerWidget.class);
        cleanIntent.setAction(ACTION_CLEAN_RAM);
        PendingIntent cleanPi = PendingIntent.getBroadcast(
                context, appWidgetId, cleanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_clean_btn, cleanPi);

        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                context, appWidgetId + 1000, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);

        manager.updateAppWidget(appWidgetId, views);
    }

    private static boolean hasRoot() {
        return MagiskRoot.get().isGranted() || MagiskRoot.get().probeQuick();
    }

    private static class CleanResult {
        int closed;
        double freedGb;
        boolean hasRoot;
    }

    /** Force-close non-protected running packages (validated package names only). */
    private static CleanResult runForceClean(Context context) {
        CleanResult cr = new CleanResult();
        long before = readAvailBytes(context);
        cr.hasRoot = hasRoot();
        MagiskRoot magisk = MagiskRoot.get();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return cr;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            Set<String> seen = new HashSet<>();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pkgList == null) continue;
                    for (String pkg : p.pkgList) {
                        if (seen.contains(pkg)) continue;
                        if (!ProtectedPackages.isValidPackage(pkg)) continue;
                        if (ProtectedPackages.isProtected(context, pkg)) continue;
                        seen.add(pkg);
                        if (cr.hasRoot && magisk.isRealRoot()) {
                            magisk.forceStopPackage(pkg);
                            if (p.pid > 0) {
                                magisk.run("kill -9 " + p.pid);
                            }
                        } else {
                            try {
                                am.killBackgroundProcesses(pkg);
                            } catch (Exception ignored) {}
                        }
                        cr.closed++;
                    }
                }
            }
            // Non-vital OEM helpers (capped)
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                int extra = 0;
                for (ApplicationInfo ai : apps) {
                    if (extra >= 40) break;
                    if (!ProtectedPackages.isValidPackage(ai.packageName)) continue;
                    if (ProtectedPackages.isProtected(context, ai.packageName) || seen.contains(ai.packageName)) {
                        continue;
                    }
                    String p = ai.packageName.toLowerCase(Locale.US);
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (system && !(p.contains("game") || p.contains("tips")
                            || p.contains("theme") || p.startsWith("com.samsung.android.app."))) {
                        continue;
                    }
                    // Never auto-clean Device Care / Knox / launcher via OEM pass
                    if (p.contains("lool") || p.contains("knox") || p.contains("launcher")) continue;
                    if (cr.hasRoot && magisk.isRealRoot()) {
                        magisk.forceStopPackage(ai.packageName);
                    } else {
                        try {
                            am.killBackgroundProcesses(ai.packageName);
                        } catch (Exception ignored) {}
                    }
                    cr.closed++;
                    seen.add(ai.packageName);
                    extra++;
                }
            } catch (Exception ignored) {
            }
            System.gc();
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {}
        } catch (Exception ignored) {
        }
        long after = readAvailBytes(context);
        cr.freedGb = Math.max(0, after - before) / (1024.0 * 1024.0 * 1024.0);
        return cr;
    }

    private static long readAvailBytes(Context context) {
        try {
            return RamMetrics.sample(context).availKb * 1024L;
        } catch (Exception e) {
            return 0;
        }
    }
}
