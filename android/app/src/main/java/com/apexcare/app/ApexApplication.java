package com.apexcare.app;

import android.app.Application;
import android.os.Build;
import android.webkit.WebView;

/**
 * Must run before any WebView is constructed (Samsung multi-process provider crash).
 */
public class ApexApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                WebView.setDataDirectorySuffix("apex");
            }
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                WebView.setWebContentsDebuggingEnabled(false);
            }
        } catch (Throwable ignored) {}
    }
}
