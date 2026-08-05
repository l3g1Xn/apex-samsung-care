package com.apexcare.app;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAM Cleaner widget — primary display is free RAM percentage.
 * Uses multi-sample median of /proc/meminfo MemAvailable for accuracy.
 */
public class RamCleanerWidget extends AppWidgetProvider {

    public static final String ACTION_CLEAN = "com.apexcare.app.ACTION_CLEAN_RAM";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_CLEAN.equals(intent.getAction())) {
            // Trigger clean via DeviceBridge if possible; refresh UI
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, RamCleanerWidget.class);
            int[] ids = mgr.getAppWidgetIds(thisWidget);
            onUpdate(context, mgr, ids);
        }
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_ram_cleaner);

        long[] mem = medianAvailFromProc();
        long availKb = mem[0];
        long totalKb = mem[1];
        int freePct = totalKb > 0 ? (int) Math.round(100.0 * availKb / totalKb) : 0;

        views.setTextViewText(R.id.widget_free_pct, freePct + "% free");
        String freeLine = String.format("%.1f GB free of %.1f GB",
                availKb / (1024.0 * 1024.0),
                totalKb / (1024.0 * 1024.0));
        views.setTextViewText(R.id.widget_free_line, freeLine);

        Intent intent = new Intent(context, RamCleanerWidget.class);
        intent.setAction(ACTION_CLEAN);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_clean_btn, pi);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /** Multi-sample median MemAvailable + MemTotal from /proc/meminfo (KB). */
    static long[] medianAvailFromProc() {
        List<Long> avails = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < 5; i++) {
            long[] sample = readMeminfo();
            if (sample[0] > 0) avails.add(sample[0]);
            if (sample[1] > 0) total = sample[1];
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        if (avails.isEmpty()) return new long[]{0, total};
        Collections.sort(avails);
        long median = avails.get(avails.size() / 2);
        return new long[]{median, total};
    }

    static long[] readMeminfo() {
        long avail = 0, total = 0, free = 0, buffers = 0, cached = 0, sreclaim = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = parseKb(line);
                else if (line.startsWith("MemAvailable:")) avail = parseKb(line);
                else if (line.startsWith("MemFree:")) free = parseKb(line);
                else if (line.startsWith("Buffers:")) buffers = parseKb(line);
                else if (line.startsWith("Cached:")) cached = parseKb(line);
                else if (line.startsWith("SReclaimable:")) sreclaim = parseKb(line);
            }
        } catch (Exception ignored) {}
        if (avail <= 0) avail = free + buffers + cached + sreclaim;
        return new long[]{avail, total};
    }

    static long parseKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) return Long.parseLong(parts[1]);
        } catch (Exception ignored) {}
        return 0;
    }
}
