package com.apexcare.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ApexCare";
    private static final String ASSET_URL = "file:///android_asset/www/index.html";
    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge friendly on gesture-nav Samsung devices
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        RamMetrics.sample(this); // hardware RAM scan + cache on install/open
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        // Local asset only — do not open universal/file cross-origin holes
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(true);
        settings.setBlockNetworkLoads(true); // offline UI — no remote fetch
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay inside packaged asset UI only
                if (request == null || request.getUrl() == null) return true;
                String u = request.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return false;
                // Allow GitHub links to open externally via system
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
        webView.loadUrl(ASSET_URL);

        // Safe-area padding for notched / camera-cutout Samsung displays
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // Refresh cached HW total if prefs expired (no-op when warm)
        try {
            RamMetrics.sample(this);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
