package com.apexcare.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

/**
 * Runs before any ContentProvider / WebView.
 * Must not exec su, must not construct a WebView.
 */
public class ApexApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Providers init between attachBaseContext and onCreate — suffix must be first.
        disableWebViewDataDirConflict();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        disableWebViewDataDirConflict();
        try {
            if (Build.VERSION.SDK_INT >= 19) {
                WebView.setWebContentsDebuggingEnabled(false);
            }
        } catch (Throwable ignored) {}
    }

    private static void disableWebViewDataDirConflict() {
        try {
            // API 28+: avoids Samsung multi-process WebView provider crash.
            if (Build.VERSION.SDK_INT >= 28) {
                WebView.setDataDirectorySuffix("apex");
            }
        } catch (Throwable ignored) {}
    }
}
