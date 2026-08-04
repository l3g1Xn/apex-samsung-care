# Apex Care Android

Native WebView shell for **v0.1.5-beta** (versionCode 15).

## Modules

- `MainActivity` — loads `assets/www/index.html` (badge shows **v0.1.5 beta**)
- `DeviceBridge` (`ApexNative`) — scan, heuristics, memory stats, protect-aware close
- `RamCleanerWidget` — resizable transparent RAM cleaner

## Build

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
# optional signing via keystore.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
