package com.apexcare.app;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.Locale;

public class RamCleanerWidget extends AppWidgetProvider {

    public static final String ACTION_CLEAN_RAM = "com.apexcare.app.ACTION_CLEAN_RAM";
    public static final String ACTION_REFRESH = "com.apexcare.app.ACTION_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, manager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CLEAN_RAM.equals(action)) {
            runClean(context);
            refreshAll(context);
            Toast.makeText(context, "Apex Care \u00b7 RAM cleaned", Toast.LENGTH_SHORT).show();
        } else if (ACTION_REFRESH.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            refreshAll(context);
        }
    }

    private static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, RamCleanerWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_ram_cleaner);

        MemoryStats stats = readStats(context);
        views.setTextViewText(R.id.widget_ram,
                String.format(Locale.US, "%.1f GB free", stats.freeRamGb));
        views.setTextViewText(R.id.widget_storage,
                String.format(Locale.US, "Storage %.0f%% \u00b7 %.1f GB used",
                        stats.storageUsedPct, stats.usedStorageGb));

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

    private static void runClean(Context context) {
        try {
            System.gc();
            System.runFinalization();
            System.gc();
            SystemClock.sleep(80);
        } catch (Exception ignored) {
        }
    }

    static MemoryStats readStats(Context context) {
        MemoryStats s = new MemoryStats();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                s.freeRamGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
                s.totalRamGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
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
            s.totalStorageGb = total / (1024.0 * 1024.0 * 1024.0);
            if (total > 0) {
                s.storageUsedPct = (used * 100.0) / total;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    static class MemoryStats {
        double freeRamGb = 0;
        double totalRamGb = 0;
        double usedStorageGb = 0;
        double totalStorageGb = 0;
        double storageUsedPct = 0;
    }
}
