package com.apexcare.app;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local packaged UI only. Primary load is {@code loadDataWithBaseURL} so Samsung
 * WebView never has to hit the network stack for first paint. Asset loader still
 * intercepts the dummy HTTPS origin. INTERNET is declared because Chromium on
 * One UI throws {@code SecurityException} without it even for local content.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ApexCare";
    private static final String ASSET_HTTPS =
            "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private final ExecutorService ramWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apex-ram-scan");
        t.setDaemon(true);
        return t;
    });

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        } catch (Throwable ignored) {}
        try {
            RamMetrics.sampleFast(this);
        } catch (Throwable ignored) {}
        ramWorker.execute(() -> {
            try {
                RamMetrics.sampleThorough(MainActivity.this);
            } catch (Throwable ignored) {}
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080A);
        setContentView(root);

        try {
            bootWebView(root);
        } catch (Throwable t) {
            Log.e(TAG, "WebView bootstrap failed", t);
            showWebViewMissing(root, t);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void bootWebView(FrameLayout root) {
        // Do not inflate WebView from XML — Samsung provider updates crash inflation.
        webView = new WebView(this);
        webView.setId(View.generateViewId());
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        try {
            WebView.setWebContentsDebuggingEnabled(false);
        } catch (Throwable ignored) {}

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        try {
            settings.setDatabaseEnabled(false);
        } catch (Throwable ignored) {}
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        try {
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setAllowFileAccessFromFileURLs(false);
        } catch (Throwable ignored) {}
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(true);
        // false: loadDataWithBaseURL must reach the document. Interceptor 403s real net.
        settings.setBlockNetworkLoads(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return blocked();
                }
                try {
                    WebResourceResponse served = assetLoader.shouldInterceptRequest(request.getUrl());
                    if (served != null) return served;
                } catch (Throwable ignored) {}
                return blocked();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                String u = request.getUrl().toString();
                if (u.startsWith("https://appassets.androidplatform.net/assets/")) return false;
                if (u.startsWith("https://github.com/l3g1Xn/apex-samsung-care")) {
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception ignored) {}
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + (error != null ? error.getDescription() : ""));
                    new Handler(Looper.getMainLooper()).post(() -> loadFromAssets());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Page finished: " + url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, consoleMessage.message()
                        + " @" + consoleMessage.sourceId()
                        + ":" + consoleMessage.lineNumber());
                return true;
            }
        });
        webView.setBackgroundColor(0xFF07080A);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        try {
            ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
                Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
                return insets;
            });
        } catch (Throwable ignored) {}

        loadFromAssets();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null) {
                    try {
                        if (webView.canGoBack()) {
                            webView.goBack();
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /** Primary UI path — never loadUrl(https) (Samsung SecurityException without net). */
    private void loadFromAssets() {
        if (webView == null) return;
        try (InputStream in = getAssets().open("www/index.html");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            String html = bos.toString(StandardCharsets.UTF_8.name());
            webView.loadDataWithBaseURL(
                    ASSET_HTTPS,
                    html,
                    "text/html",
                    "utf-8",
                    null);
            Log.i(TAG, "Loaded UI via loadDataWithBaseURL");
        } catch (Exception e) {
            Log.e(TAG, "asset load failed", e);
        }
    }

    private void showWebViewMissing(FrameLayout root, Throwable t) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFEEF1F4);
        tv.setPadding(48, 96, 48, 48);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setText("Apex Care needs Android System WebView.\n\n"
                + "Settings → Apps → Android System WebView → Enable\n\n"
                + "Then reopen Apex Care.\n\n"
                + (t != null && t.getMessage() != null ? t.getMessage() : ""));
        root.removeAllViews();
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            try {
                webView.onResume();
            } catch (Throwable ignored) {}
        }
        try {
            RamMetrics.sampleFast(this);
        } catch (Throwable ignored) {}
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
