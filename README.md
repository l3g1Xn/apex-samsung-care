# Apex Care

**v0.1.4 beta** — Device Care–style control with on-device package scan, local heuristic **Safe** scanner, and a resizable **RAM** home-screen widget.

## Features

| Area | What it does |
|------|----------------|
| **Health** | Live RAM-based score (no fixed phone-model label) |
| **Apps** | Real `PackageManager` inventory — user / system / disabled |
| **Safe** | Local heuristics: install source, permissions, debuggable, target SDK |
| **Widget** | Free RAM + **Clean** button, horizontal/vertical resize |

Store / deep-storage UI is **not** included in this build.

## Repository layout

```
apex-samsung-care/
├── README.md
├── .gitignore
└── android/                 # Native APK project (primary product)
    ├── README.md
    ├── settings.gradle
    ├── build.gradle
    ├── gradle.properties
    ├── gradle/wrapper/
    └── app/
        ├── build.gradle
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/apexcare/app/
            │   ├── MainActivity.java
            │   ├── DeviceBridge.java
            │   └── RamCleanerWidget.java
            ├── assets/www/index.html
            └── res/
```

## Android build

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

| Field | Value |
|-------|--------|
| Package | `com.apexcare.app` |
| Version | `0.1.4-beta` (versionCode **14**) |
| Min / target | API 26 / 34 |

Release APK: see [Releases](https://github.com/l3g1Xn/apex-samsung-care/releases).

## Notes

- Sideloaded / demo signing — not Play Store.
- Heuristic findings are **signals**, not cloud malware verdicts.
- OEM-level process kill remains system-privileged on stock Android.
- Package size stays under the ~56 MB budget (~4.3 MB APK).

## License

Demo / portfolio project. Not affiliated with Samsung.
