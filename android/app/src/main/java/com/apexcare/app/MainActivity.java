package com.apexcare.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Platform Activity + file:// packaged UI. No Jetpack (AppCompat / WebKit / Emoji2)
 * — those initializers crash on a slice of One UI / China / no-GMS SKUs.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ApexCare";
    private static final String ASSET_FILE = "file:///android_asset/www/index.html";

    private WebView webView;
    private final ExecutorService ramWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apex-ram-scan");
        t.setDaemon(true);
        return t;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {}
        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable t) {
            Log.e(TAG, "super.onCreate", t);
            showFatal(t);
            return;
        }
        try {
            paintSystemBars();
        } catch (Throwable ignored) {}

        try {
            final FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(0xFF07080A);
            setContentView(root);
            // Defer WebView until first layout — Samsung provider native-crashes
            // if constructed during Activity super.onCreate / before window attach.
            root.post(() -> {
                try {
                    bootWebView(root);
                } catch (Throwable t) {
                    Log.e(TAG, "WebView bootstrap failed", t);
                    showWebViewMissing(root, t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "onCreate UI", t);
            showFatal(t);
            return;
        }

        ramWorker.execute(() -> {
            try {
                RamMetrics.sampleFast(MainActivity.this);
            } catch (Throwable ignored) {}
        });
    }

    private void paintSystemBars() {
        Window w = getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(0xFF07080A);
            w.setNavigationBarColor(0xFF101214);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                View decor = w.getDecorView();
                int vis = decor.getSystemUiVisibility();
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                decor.setSystemUiVisibility(vis);
            } catch (Throwable ignored) {}
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void bootWebView(FrameLayout root) {
        webView = new WebView(this);
        try {
            boolean samsung = manufacturerIsSamsung();
            // Software layer on S6–S20-class Mali/Exynos WebView (GPU compositor crashes).
            if (samsung && Build.VERSION.SDK_INT < 31) {
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        } catch (Throwable ignored) {}
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            if (Build.VERSION.SDK_INT >= 19) {
                WebView.setWebContentsDebuggingEnabled(false);
            }
        } catch (Throwable ignored) {}

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        try {
            settings.setDatabaseEnabled(false);
        } catch (Throwable ignored) {}
        // file:///android_asset is the only load that every Samsung WebView fork
        // from Lollipop through One UI 8 will paint without Chromium SecurityException.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        try {
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setAllowFileAccessFromFileURLs(false);
        } catch (Throwable ignored) {}
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(true);
        settings.setBlockNetworkLoads(true);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                settings.setSafeBrowsingEnabled(false);
            } catch (Throwable ignored) {}
        }
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        webView.addJavascriptInterface(new DeviceBridge(this), "ApexNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                String u = request.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return false;
                if (u.startsWith("https://github.com/l3g1Xn/apex-samsung-care")) {
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception ignored) {}
                }
                return true;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return true;
                if (url.startsWith("file:///android_asset/")) return false;
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + (error != null ? error.getDescription() : ""));
                    new Handler(Looper.getMainLooper()).post(() -> loadInlineFallback());
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error legacy: " + description);
                new Handler(Looper.getMainLooper()).post(() -> loadInlineFallback());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Page finished: " + url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage == null) return true;
                Log.d(TAG, consoleMessage.message()
                        + " @" + consoleMessage.sourceId()
                        + ":" + consoleMessage.lineNumber());
                return true;
            }
        });
        webView.setBackgroundColor(0xFF07080A);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        loadFromAssets();
    }

    private static boolean manufacturerIsSamsung() {
        try {
            String m = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
            String b = Build.BRAND != null ? Build.BRAND : "";
            return "samsung".equalsIgnoreCase(m) || "samsung".equalsIgnoreCase(b);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Primary UI path — file:///android_asset, never https. */
    private void loadFromAssets() {
        if (webView == null) return;
        try {
            webView.loadUrl(ASSET_FILE);
            Log.i(TAG, "Loaded UI via file android_asset");
        } catch (Throwable e) {
            Log.e(TAG, "asset loadUrl failed", e);
            loadInlineFallback();
        }
    }

    private void loadInlineFallback() {
        if (webView == null) return;
        try (InputStream in = getAssets().open("www/index.html");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            String html = new String(bos.toByteArray(), Charset.forName("UTF-8"));
            webView.loadDataWithBaseURL(
                    "file:///android_asset/www/",
                    html,
                    "text/html",
                    "utf-8",
                    null);
            Log.i(TAG, "Loaded UI via loadDataWithBaseURL fallback");
        } catch (Throwable e) {
            Log.e(TAG, "inline fallback failed", e);
            try {
                webView.loadDataWithBaseURL(
                        "file:///android_asset/www/",
                        "<!doctype html><html><body style='background:#07080A;color:#eef1f4;font-family:sans-serif;padding:24px'><h1>Apex Care</h1><p>UI failed to load. Reinstall the v1.0.4 APK.</p></body></html>",
                        "text/html",
                        "utf-8",
                        null);
            } catch (Throwable ignored) {}
        }
    }

    private void showWebViewMissing(FrameLayout root, Throwable t) {
        try {
            TextView tv = fatalView(t);
            root.removeAllViews();
            root.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {}
    }

    private void showFatal(Throwable t) {
        try {
            setContentView(fatalView(t));
        } catch (Throwable ignored) {}
    }

    private TextView fatalView(Throwable t) {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(48, 96, 48, 48);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(0xFF07080A);
        tv.setText("Apex Care needs Android System WebView.\n\n"
                + "Settings → Apps → Android System WebView → Enable\n\n"
                + "Then reopen Apex Care.\n\n"
                + (t != null && t.getMessage() != null ? t.getMessage() : ""));
        return tv;
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            try {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
            } catch (Throwable ignored) {}
        }
        try {
            super.onBackPressed();
        } catch (Throwable ignored) {
            try {
                finish();
            } catch (Throwable ignored2) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            try {
                webView.onResume();
            } catch (Throwable ignored) {}
        }
        ramWorker.execute(() -> {
            try {
                RamMetrics.sampleFast(MainActivity.this);
            } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            try {
                webView.onPause();
            } catch (Throwable ignored) {}
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ramWorker.shutdownNow();
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("ApexNative");
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.setWebViewClient(null);
                webView.setWebChromeClient(null);
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
