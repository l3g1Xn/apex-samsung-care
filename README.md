# APEX CARE v1.0.0

### Make RAM Great Again · Full Send Edition

This is not your soft, soy, "please-optimize-my-feelings" device-care toy.  
This is a **steel-toed boot** for background bloat, a **Hot Wheels flame job** for free RAM percentage, and a middle finger to every process that thinks your S25 Ultra is a goddamn public park.

Sideloaded. Unsigned by the Play Store priesthood. Root-optional but root-hungry.  
If that scares you, close the tab and go reinstall TikTok.

---

## What this beast does

| Area | What it actually does |
|------|------------------------|
| **RAM (pixel-level)** | Free % from `/proc/meminfo` **MemAvailable** multi-sample median — not marketing fluff |
| **Optimize / Clean** | Force-closes non-vital running apps. With root: real `am force-stop` + PID kill. Without: background kill |
| **Running Apps & Processes** | Live list with PSS. Force close removes them from the list like they never existed |
| **Grant Temporary Root** | In-app + widget path to probe Magisk / KernelSU / `su`. **Android cannot auto-grant root on APK install** — anyone who tells you otherwise is selling snake oil |
| **Safe** | Local malware / PUA heuristics; optional auto force-close of open high/medium risks |
| **Widget** | Free % primary, ROOT badge when elevated, deeper Clean when su is live |
| **Nav** | Properly scaled bottom bar: Care · Running · Safe · More |
| **Splash** | MAGA Hot Wheels style logo overlay that vanishes after open. Flames optional. Tears optional. |

---

## Install (for people with working hands)

1. **Uninstall any older Apex Care** (Settings → Apps → Apex Care → Uninstall). Required when the signing cert changes.
2. Download **ApexCare-v1.0.0.apk** from [Releases](https://github.com/l3g1Xn/apex-samsung-care/releases).
3. Allow install from that source. Install the **signed** universal APK.
4. Open the app. Watch the flame logo. Tap **Grant Temporary Root** if Magisk/su is already on the device.
5. Badge should read **v1.0.0**.

If Samsung says **"App not installed"**: you still have an old build, or you grabbed an unsigned APK. Uninstall first, then use the latest release asset only.

**versionCode 18 · package `com.apexcare.app` · minSdk 24 / targetSdk 34 · signed v1+v2+v3 universal**

---

## Root reality check (read this before you invent a fantasy)

- **No APK can "trigger root on install" through normal Android permissions.**  
  That is not how the OS works. `REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, storage, camera — none of those are `uid=0`.
- Temporary elevated clearing works **only** if Magisk, KernelSU, or another su binary is already present and the user grants this app.
- Without root you still get accurate free-RAM %, background kill, Safe scan, and the widget. With root you get the full FORCE_CLOSE hammer.

If that paragraph makes someone "whine harder," good. Physics is not a participation trophy.

---

## Build it yourself (optional)

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

Root is optional. Whining is optional. Accuracy is not.

---

## Safety

- Protect list: core OS, telephony, keyboard, Play services, Apex itself — never force-closed.
- Heuristics are **on-device**. No cloud snitch network.
- Demo signing. Install only if you trust this repo and your own judgment.

---

## License / affiliation

Demo / portfolio project. **Not affiliated with Samsung, Google, Hot Wheels, or the Republican Party.**  
Flames, chrome, and attitude are decorative. The RAM math is not.

---

**Apex Care v1.0.0 — Full Send.**  
If your free-RAM bubble goes up and your blood pressure goes down, you're welcome.  
If it offends you that software can be both accurate and aggressive: touch grass, then clear cache.
