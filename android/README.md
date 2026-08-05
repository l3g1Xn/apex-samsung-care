# Apex Care Android

Native WebView shell for **v0.1.6-beta** (versionCode 16).

## Modules

- `MainActivity` — loads `assets/www/index.html` (badge shows **v0.1.6 beta**)
- `DeviceBridge` (`ApexNative`) — scan, heuristics, memory stats, protect-aware close, optimize
- `RamCleanerWidget` — resizable transparent RAM cleaner with run animation

## Build

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
