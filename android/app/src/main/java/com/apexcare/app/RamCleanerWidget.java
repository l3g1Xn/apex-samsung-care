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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
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

    private static final Set<String> PROTECTED = new HashSet<>();
    static {
        PROTECTED.add("android");
        PROTECTED.add("com.android.systemui");
        PROTECTED.add("com.android.settings");
        PROTECTED.add("com.android.phone");
        PROTECTED.add("com.android.server.telecom");
        PROTECTED.add("com.google.android.gms");
        PROTECTED.add("com.google.android.gsf");
        PROTECTED.add("com.android.inputmethod.latin");
        PROTECTED.add("com.google.android.inputmethod.latin");
        PROTECTED.add("com.android.permissioncontroller");
        PROTECTED.add("com.google.android.permissioncontroller");
        PROTECTED.add("com.android.providers.settings");
        PROTECTED.add("com.android.providers.telephony");
        PROTECTED.add("com.android.bluetooth");
        PROTECTED.add("com.android.nfc");
        PROTECTED.add("com.android.keychain");
        PROTECTED.add("com.android.shell");
    }

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
            // Animate Clean button across all widgets
            setAllButtons(context, "…");
            HANDLER.postDelayed(() -> setAllButtons(context, "···"), 180);
            HANDLER.postDelayed(() -> {
                int closed = runClean(context);
                refreshAll(context);
                setAllButtons(context, "Done");
                Toast.makeText(context,
                        "Apex Care · closed " + closed + " bg apps",
                        Toast.LENGTH_SHORT).show();
            }, 420);
            HANDLER.postDelayed(() -> setAllButtons(context, context.getString(R.string.widget_clean)), 1600);
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

        MemoryStats stats = readStats(context);
        views.setTextViewText(R.id.widget_ram,
                String.format(Locale.US, "%.1f / %.0f GB free", stats.freeRamGb, stats.totalRamGb));
        views.setTextViewText(R.id.widget_storage,
                String.format(Locale.US, "Disk %.0f%% used · %.1f GB free",
                        stats.storageUsedPct, stats.freeStorageGb));
        views.setTextViewText(R.id.widget_meta,
                String.format(Locale.US, "%d procs · %s",
                        stats.runningProcesses,
                        stats.model != null && !stats.model.isEmpty() ? stats.model : "Android"));

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

    private static boolean isProtected(Context context, String pkg) {
        if (pkg == null) return true;
        if (pkg.equals(context.getPackageName())) return true;
        if (PROTECTED.contains(pkg)) return true;
        String lower = pkg.toLowerCase(Locale.US);
        return lower.contains("telecom") || lower.contains("telephony")
                || lower.contains("inputmethod") || pkg.startsWith("com.android.providers.");
    }

    /** Kill non-protected background processes; return count attempted. */
    private static int runClean(Context context) {
        int closed = 0;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            Set<String> seen = new HashSet<>();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue;
                    if (p.pkgList == null) continue;
                    for (String pkg : p.pkgList) {
                        if (seen.contains(pkg) || isProtected(context, pkg)) continue;
                        seen.add(pkg);
                        try {
                            am.killBackgroundProcesses(pkg);
                            closed++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            // Also hit installed non-vital packages
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo ai : apps) {
                    if (isProtected(context, ai.packageName)) continue;
                    if (seen.contains(ai.packageName)) continue;
                    String p = ai.packageName.toLowerCase(Locale.US);
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (system && !(p.contains("lool") || p.contains("game") || p.contains("tips")
                            || p.contains("theme") || p.startsWith("com.samsung.android.app."))) {
                        continue;
                    }
                    try {
                        am.killBackgroundProcesses(ai.packageName);
                        closed++;
                        seen.add(ai.packageName);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            System.gc();
        } catch (Exception ignored) {
        }
        return closed;
    }

    static MemoryStats readStats(Context context) {
        MemoryStats s = new MemoryStats();
        try {
            s.model = BuildModel();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                s.freeRamGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
                s.totalRamGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                s.runningProcesses = procs != null ? procs.size() : 0;
            }
        } catch (Exception ignored) {
        }
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * blockSize;
            long avail = stat.getAvailableBlocksLong() * blockSize;
            long used = total - avail;
            s.usedStorageGb = used / (1024.0 * 1024.0 * 1024.0);
            s.freeStorageGb = avail / (1024.0 * 1024.0 * 1024.0);
            s.totalStorageGb = total / (1024.0 * 1024.0 * 1024.0);
            if (total > 0) s.storageUsedPct = (used * 100.0) / total;
        } catch (Exception ignored) {
        }
        return s;
    }

    private static String BuildModel() {
        try {
            String m = android.os.Build.MODEL;
            return m != null ? m : "";
        } catch (Exception e) {
            return "";
        }
    }

    static class MemoryStats {
        double freeRamGb = 0;
        double totalRamGb = 0;
        double usedStorageGb = 0;
        double freeStorageGb = 0;
        double totalStorageGb = 0;
        double storageUsedPct = 0;
        int runningProcesses = 0;
        String model = "";
    }
}
