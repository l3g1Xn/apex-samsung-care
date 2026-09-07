package com.apexcare.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
 * Local packaged UI only. Asset loader serves https://appassets.androidplatform.net
 * so we never enable file-URL cross-origin. All other network is intercepted 403.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ApexCare";
    private static final String ASSET_HTTPS =
            "https://appassets.androidplatform.net/assets/www/index.html";
    private static final String ASSET_FILE = "file:///android_asset/www/index.html";

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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        // Fast path on UI thread — never Thread.sleep here (ANR on low-end One UI)
        try {
            RamMetrics.sampleFast(this);
        } catch (Exception ignored) {}
        ramWorker.execute(() -> {
            try {
                RamMetrics.sampleThorough(MainActivity.this);
            } catch (Exception ignored) {}
        });
        setContentView(R.layout.activity_main);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = findViewById(R.id.webview);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(true);
        // AssetLoader needs the request to reach shouldInterceptRequest
        settings.setBlockNetworkLoads(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(true);
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
                WebResourceResponse served = assetLoader.shouldInterceptRequest(request.getUrl());
                if (served != null) return served;
                String host = request.getUrl().getHost();
                if (host != null && host.equals("appassets.androidplatform.net")) {
                    return blocked();
                }
                return blocked();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                String u = request.getUrl().toString();
                if (u.startsWith("https://appassets.androidplatform.net/assets/")) return false;
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "WebView error: " + error.getDescription());
                    // Fallback to file asset if https asset loader path fails
                    String failing = request.getUrl() != null ? request.getUrl().toString() : "";
                    if (failing.startsWith("https://appassets.androidplatform.net")
                            && webView != null) {
                        new Handler(Looper.getMainLooper()).post(() -> loadFromAssets());
                    }
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
        webView.loadUrl(ASSET_HTTPS);

        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /** Fallback without file:// — keeps allowFileAccess=false. */
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
            Log.i(TAG, "Loaded UI via loadDataWithBaseURL fallback");
        } catch (Exception e) {
            Log.e(TAG, "asset fallback failed", e);
        }
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
        if (webView != null) webView.onResume();
        try {
            RamMetrics.sampleFast(this);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ramWorker.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("ApexNative");
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
