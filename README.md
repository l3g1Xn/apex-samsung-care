# Apex Care

**v0.1.7 beta** — Device Care–style Android control: accurate RAM, **root force-close** of running apps & processes, on-device malware/PUA Safe scan with auto-close, and a resizable RAM widget.

> Not affiliated with Samsung. Sideloaded demo build — not on the Play Store.

## Current release

| Field | Value |
|-------|--------|
| Version | **0.1.7-beta** |
| versionCode | **17** |
| Package | `com.apexcare.app` |
| Min / target SDK | 26 / 34 |
| APK | [ApexCare-v0.1.7-beta.apk](https://github.com/l3g1Xn/apex-samsung-care/releases/tag/v0.1.7-beta) |

**Install:** uninstall older Apex Care → download from [Releases](https://github.com/l3g1Xn/apex-samsung-care/releases) → open. Badge: **v0.1.7 beta**.

## Features

| Area | What it does |
|------|----------------|
| **RAM (accurate)** | Free/total from `/proc/meminfo` MemAvailable + ActivityManager; used % + process PSS |
| **Optimize / Clean** | **Force-closes** non-vital running apps (`am force-stop` with root; killBackground otherwise) |
| **Running Apps & Processes** | Live process list with PSS; Force close removes item from list |
| **Protect rules** | Never force-closes core OS, telephony, keyboard, Play services, Apex |
| **Safe** | On-device malware/PUA heuristics; **auto force-close open high/medium risks** (toggle) |
| **Widget** | Free/total RAM, used GB, disk free, process count; Clean with animation |
| **Nav** | Care · Running Apps & Processes · Safe · More (pinned bottom bar) |

## Build

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Root is optional but required for full Settings-style **FORCE_CLOSE**. Without root, background kill is used.

## Safety

- Heuristics are local signals, not cloud verdicts.
- Protect list blocks vital packages even with root.
- Demo signing — install only if you trust this repository.

## License

Demo / portfolio project. Not affiliated with Samsung or Google.
