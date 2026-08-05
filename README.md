<p align="center">
  <img src="docs/assets/apex-care-hero.png" alt="Apex Care — Make RAM Great Again" width="420" />
</p>

<h1 align="center">Apex Care</h1>
<p align="center"><strong>v1.0.0 · Full Send Edition</strong><br/>
<em>Make RAM Great Again</em></p>

<p align="center">
  <a href="https://github.com/l3g1Xn/apex-samsung-care/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/l3g1Xn/apex-samsung-care?style=flat-square&label=release" /></a>
  <a href="https://github.com/l3g1Xn/apex-samsung-care/releases/latest"><img alt="APK" src="https://img.shields.io/github/downloads/l3g1Xn/apex-samsung-care/total?style=flat-square&label=downloads" /></a>
</p>

---

Sideloaded Samsung device care for **One UI**. Accurate free RAM (Device Care model), force-close cleanup, Magisk-aware temporary root, and a home-screen RAM widget.

### Download

**[Apex Care - Full Send Edition v1.0.0](https://github.com/l3g1Xn/apex-samsung-care/releases/tag/v1.0.0)** · [ApexCare-v1.0.0.apk](https://github.com/l3g1Xn/apex-samsung-care/releases/download/v1.0.0/ApexCare-v1.0.0.apk)

1. Uninstall any older Apex Care  
2. Install the signed universal APK  
3. Open → Grant Temporary Root (optional) · Optimize / Clean  

Package `com.apexcare.app` · versionCode **18** · minSdk **24** / targetSdk **34** · signed v1+v2+v3

---

## What it does

| Area | Behavior |
|------|----------|
| **RAM** | Device Care model: free = available, used = total − available; hardware total scanned on install |
| **Optimize / Clean** | Force-closes non-vital apps (`am force-stop` with root; background kill without) |
| **Grant Temporary Root** | In-process Magisk `su` (no Magisk app switch) or userspace TEMP ROOT (30 min) |
| **Widget** | Free % primary · used / available GB · Clean action |
| **Safe** | On-device heuristics |

## Magisk + Temporary Root

Grant Root stays **inside Apex Care**:
1. Detects Magisk Manager when present  
2. Boot patched → Magisk Superuser via `su` (overlay only if needed)  
3. App-only / Superuser empty → userspace TEMP ROOT (30 min)  
4. Real `am force-stop` only when Magisk grants  

## Root reality

No APK can auto-grant `uid=0` on install. Magisk/KernelSU must already be on the device and allow Apex Care.

## Build

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

## Safety

Protect list: core OS, telephony, keyboard, Play services, Apex itself. Heuristics stay on-device. Demo signing for sideload only.

---

**Not affiliated with Samsung, Google, or Magisk.**  
**Apex Care v1.0.0 — Full Send.**
