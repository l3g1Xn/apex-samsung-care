# Apex Care Android

Native WebView shell + RAM widget + PackageManager bridge.

- Package: `com.apexcare.app`
- Version: `0.1.5-beta` (versionCode 15)
- Min SDK 26 / target 34

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

`DeviceBridge` exposes: `getMemoryStats`, `scanInstalledApps`, `runHeuristicScan`, `closeBackgroundApp` (protect-aware).
