# Apex Care — Android (v0.1.7-beta)

versionCode **17** · package `com.apexcare.app`

## Modules

| Class | Role |
|-------|------|
| `MainActivity` | WebView + `ApexNative` bridge |
| `DeviceBridge` | Accurate RAM, running processes + PSS, root `am force-stop`, Safe heuristics + auto-close |
| `RamCleanerWidget` | Live free/total RAM widget with force Clean animation |

## Force close

1. Prefer root: `su` → `am force-stop <package>` + `kill -9 <pid>`
2. Fallback: `ActivityManager.killBackgroundProcesses`
3. Protected packages never closed

## Build

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
