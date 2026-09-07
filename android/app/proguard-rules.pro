# Keep WebView JS bridge (never minify method names the UI calls)
-keepclassmembers class com.apexcare.app.DeviceBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.apexcare.app.DeviceBridge { *; }
-keep class com.apexcare.app.ProtectedPackages { *; }
-keep class com.apexcare.app.RamMetrics { *; }
-keep class com.apexcare.app.MagiskRoot { *; }
