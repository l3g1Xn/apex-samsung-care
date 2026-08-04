# Apex Care

**v0.1.5 beta** — Device Care–style control with **safe hang-RAM close**, OS protect rules, on-device **malware / PUA** heuristics, auto-optimize schedule, package inventory, and a resizable **RAM** home-screen widget.

## Features

| Area | What it does |
|------|----------------|
| **Health** | Live RAM + Safe score (no fixed phone-model label) |
| **Hanging RAM** | Lists hangers; **Close** only safe targets; core OS / telephony / keyboard protected |
| **Optimize** | Clears hanging apps that pass protect rules; reports MB freed |
| **Apps** | Package inventory — All / User / System / **Background** / Disabled · Hibernate · Close |
| **Safe** | Local malware + PUA heuristics: install source, weaponized perms, fake cleaners, debuggable, legacy SDK |
| **Auto-optimize** | Off / Nightly / Twice daily (persisted preference) |
| **Widget** | Free RAM + **Clean** button, horizontal/vertical resize |

Store / deep-storage UI is **not** included in this build.

## Repository layout

```
apex-samsung-care/
├── README.md
├── .gitignore
├── .github/workflows/
└── android/                 # Native APK project (primary product)
    ├── README.md
    ├── settings.gradle
    ├── build.gradle
    ├── gradle.properties
    ├── gradle/wrapper/
    └── app/
        ├── build.gradle     # versionName 0.1.5-beta, versionCode 15
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/apexcare/app/
            │   ├── MainActivity.java
            │   ├── DeviceBridge.java   # scan, heuristics, safe close
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
| Version | `0.1.5-beta` (versionCode **15**) |
| Min / target | API 26 / 34 |

Release APK: see [Releases](https://github.com/l3g1Xn/apex-samsung-care/releases).

## Notes

- Sideloaded / demo signing — not Play Store.
- Heuristic findings are **signals**, not cloud malware verdicts.
- Third-party apps cannot OEM-force-stop other UIDs on stock Android; protect rules still refuse core OS packages.
- Package size stays under the ~56 MB budget (~4.3 MB APK).

## License

Demo / portfolio project. Not affiliated with Samsung.
