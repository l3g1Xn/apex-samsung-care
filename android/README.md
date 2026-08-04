# Apex Care — Android

Native shell for **v0.1.4-beta**.

## Modules

- `MainActivity` — loads `assets/www/index.html`
- `DeviceBridge` (`ApexNative`) — `scanInstalledApps()`, `runHeuristicScan()`, memory stats
- `RamCleanerWidget` — resizable transparent RAM cleaner

## Permissions

- `INTERNET`
- `QUERY_ALL_PACKAGES` (full package inventory on Android 11+)

## Build

Requires JDK 17+, Android SDK platform 34, build-tools 34.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
