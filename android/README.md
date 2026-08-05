# Apex Care — Android

Native shell for **Apex Care v0.1.6-beta** (`versionCode` **16**).

## Modules

| Class | Role |
|-------|------|
| `MainActivity` | Loads `assets/www/index.html` in a WebView; injects `ApexNative` |
| `DeviceBridge` | Package scan, memory stats, malware/PUA heuristics, protect-aware close, `optimizeDevice()` |
| `RamCleanerWidget` | Resizable widget: free/total RAM, disk free, process count, animated Clean |

## UI

Single-page UI in `app/src/main/assets/www/index.html`:

- Tabs: **Care · Apps · Safe · More** (pinned bottom nav; Apps list scrolls independently)
- Badge: **v0.1.6 beta**
- GitHub link on More (no in-app APK download button)

## Permissions

- `INTERNET`
- `QUERY_ALL_PACKAGES` — full inventory on Android 11+
- `KILL_BACKGROUND_PROCESSES` — close non-vital background apps
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (max SDK 32)
- `MANAGE_EXTERNAL_STORAGE` — optional full-disk access for safe junk trim during optimize

## Build

```bash
# From this directory
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Optional signing (keystore.properties next to this README):
#   storeFile=../apex-release.jks
#   storePassword=…
#   keyAlias=…
#   keyPassword=…

./gradlew assembleRelease
```

| Field | Value |
|-------|--------|
| `applicationId` | `com.apexcare.app` |
| `versionName` | `0.1.6-beta` |
| `versionCode` | `16` |
| `minSdk` / `targetSdk` | 26 / 34 |
| Output | `app/build/outputs/apk/release/app-release.apk` |

## Widget

1. Long-press home → **Widgets** → **Apex Care · RAM**
2. Resize as needed
3. **Clean** animates while killing non-vital background processes

## Notes

- Do not commit `local.properties`, `*.jks`, or `keystore.properties`.
- Protect list blocks core OS / telephony / keyboard / Play services / self.
- Non-vital OEM bloat (e.g. some Samsung helper packages) may be background-closed during optimize.
