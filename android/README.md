# Apex Care — Android (v1.0.0)

versionCode **18** · package `com.apexcare.app` · minSdk **24** / targetSdk **34**

## Signed universal APK

Release builds **always** sign with `android/keystore/apex-release.jks` (v1 + v2 + v3).
Unsigned APKs fail install on Samsung ("App not installed").

## Modules

| Class | Role |
|-------|------|
| `MainActivity` | WebView + `ApexNative` bridge |
| `DeviceBridge` | MemAvailable median RAM, root force-stop, Safe heuristics |
| `RamCleanerWidget` | Free-% primary, ROOT badge, real Clean |

## Build

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed)

Verify:

```bash
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```
