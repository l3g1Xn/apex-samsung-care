# Apex Care

**v0.1.6 beta** — Device Care–style control for Android: health score, safe hang-RAM close with OS protect rules, on-device malware / PUA heuristics, package inventory, auto-optimize schedule, and a resizable **RAM** home-screen widget.

> Not affiliated with Samsung. Sideloaded demo build — not on the Play Store.

## Current release

| Field | Value |
|-------|--------|
| Version | **0.1.6-beta** |
| versionCode | **16** |
| Package | `com.apexcare.app` |
| Min / target SDK | 26 / 34 |
| APK | [ApexCare-v0.1.6-beta.apk](https://github.com/l3g1Xn/apex-samsung-care/releases/tag/v0.1.6-beta) (~4.3 MB) |

**Install:** uninstall any older Apex Care → download the APK from [Releases](https://github.com/l3g1Xn/apex-samsung-care/releases) → open. Header badge should read **v0.1.6 beta**.

## Features

| Area | What it does |
|------|----------------|
| **Care · Health** | Live health score from free RAM (+ Safe blend). No fixed phone-model label. |
| **Optimize / Clean** | Closes **non-vital** background apps (user + stock bloat). Pulse animation while running. |
| **Protect rules** | Never force-closes core OS, telephony, keyboard, Play services, or Apex itself. |
| **Hanging RAM** | Surfaces hangers; **Close** only safe targets. |
| **Apps** | Package inventory — All / User / System / Background / Disabled. Bottom tabs stay pinned while scrolling. |
| **Safe** | On-device malware + PUA heuristics: install source, weaponized permissions, fake cleaners, debuggable, legacy target SDK. |
| **Auto-optimize** | Off / Nightly / Twice daily (local preference). |
| **Widget** | Free/total RAM · disk free % · process count · **Clean** with run animation. Horizontal/vertical resize. |

Store / deep-storage UI is **not** part of this product.

## Repository layout

```
apex-samsung-care/
├── README.md                 # This file
├── .gitignore
├── .github/workflows/        # Optional CI release helper
└── android/                  # Native APK (primary product)
    ├── README.md
    ├── settings.gradle
    ├── build.gradle
    ├── gradle.properties
    ├── gradle/wrapper/
    ├── gradlew
    └── app/
        ├── build.gradle      # versionName 0.1.6-beta · versionCode 16
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/apexcare/app/
            │   ├── MainActivity.java      # WebView shell
            │   ├── DeviceBridge.java      # ApexNative JS bridge
            │   └── RamCleanerWidget.java  # Home-screen widget
            ├── assets/www/index.html      # In-app UI
            └── res/
```

## Build from source

Requires **JDK 17+** and **Android SDK** (platform 34, build-tools 34).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
# Optional: keystore.properties for a signed release
./gradlew assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`

Permissions used: `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, storage (safe temp/cache trim during optimize).

## Safety notes

- Heuristic findings are **local signals**, not cloud malware verdicts.
- Stock Android does not allow third-party apps to OEM force-stop every process; protect rules still refuse vital packages.
- Demo / portfolio signing — install only from this repository’s Releases if you trust the source.

## License

Demo / portfolio project. Not affiliated with Samsung or Google.
