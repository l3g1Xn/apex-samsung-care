# Security Policy - Apex Care

**Make RAM Great Again** - `com.apexcare.app`

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.0.4   | Yes - **sole** published APK |
| 1.0.3   | No GitHub Release - upgrade to 1.0.4 |
| 1.0.2   | No GitHub Release - upgrade to 1.0.4 |
| 1.0.1   | No GitHub Release - upgrade to 1.0.4 (same demo cert) |
| 1.0.0   | No GitHub Release - upgrade recommended |
| < 1.0.0 | No |

## What this app does (threat model)

- Reads on-device RAM/storage metrics (`/proc/meminfo`, `ActivityManager`)
- Lists running packages and may force-stop **non-protected** apps
- Optional Magisk `su` for real `am force-stop` when the user grants Superuser
- UI is a **local** WebView asset (non-asset network intercepted 403)

It does **not** phone home, upload package lists, or auto-root a stock device.

## Protections (1.0.4)

- **Package name validation** before any kill / force-stop path
- **Root command allowlist** (`id`, `am kill-all`, `am force-stop <pkg>`, `cmd activity force-stop <pkg>`, `am kill <pkg>`, `cmd activity stop-app <pkg>`, `cmd activity compact <pkg> some|full`, `am send-trim-memory <pkg> RUNNING_CRITICAL|COMPLETE`). **No SIGKILL / `kill -9`.**
- **Expanded protect list** (One UI telephony, input, Knox, Magisk/KernelSU/APatch, launchers, Find My, Samsung Account, DeX, GMS, Wallet, Health, camera, gallery, IMS, Android Auto, Bixby, Secure Folder, Galaxy AI, Quick Share)
- **User protect list** (validated package names, cap 80, SharedPreferences)
- **Bulk Optimize / widget Clean skip foreground and visible** processes
- **Widget no longer uses SIGKILL** and no longer sweeps all `com.samsung.android.app.*` installs
- **Optimize / Safe scan auto-engage TEMP ROOT** (or Magisk su if already granted) before closing or scanning
- **Hanging-process retry** (pass 2 compact-full + COMPLETE trim, pass 3 leftover blobs) then `am kill-all` for empty cached CPU (NPU/GPU HALs stay protected)
- **Dropped unused `PACKAGE_USAGE_STATS`** and persistence `keep_process_alive` metadata (Play Protect surface)
- **No `INTERNET` permission** (offline WebView; Magisk probe is local files) and `extractNativeLibs=false` (no native .so)
- **No `su` exec on launch** (RAM ticks / widget only read in-process TEMP ROOT / Magisk session flags). Superuser handshake runs only after Grant Root / Optimize / Safe scan.
- **`QUERY_ALL_PACKAGES` is not requested** (Play Protect PUP surface). `INTERNET` is declared because Samsung WebView crashes on open without it; Chromium network fetches are intercepted 403.
- **WebView locked down**: `WebViewAssetLoader` (no file-URL access), no universal file access, mixed content never, stray HTTPS 403
- **Widget custom actions** are explicit-component only (not exported as implicit broadcasts)
- **UI-thread RAM sample is sleep-free** including first-open HW scan (`scanUsableRamKbFast`)
- **WebView fallback never uses file://** (`loadDataWithBaseURL` on the asset-loader origin)
- **Widget Clean is off the main looper**
- **Backup disabled** (`allowBackup=false`, data extraction rules exclude prefs)
- **Cleartext traffic disabled**

## Reporting a vulnerability

1. Open a private security advisory on GitHub if available, **or**
2. File an issue titled `[SECURITY]` with reproduction steps (no exploit chains in public if critical).

Please do **not** open PRs that demonstrate live RCE payloads against end-user devices.

## Sideload signing

Release APKs use a **sideload keystore** committed for CI (rotated 2026-09-07, `ApexCare26Sideload`). Treat it as public. **Uninstall older Apex Care before installing** — Android will not upgrade across certificates.

Play Protect often labels a first-seen sideload certificate as **Uncommon / PUP**. That is not a malware verdict. This APK is not listed on Google Play. If Play Protect blocks install, use **More details → Install anyway**. Do not disable Play Protect permanently.

`QUERY_ALL_PACKAGES` is not requested (Play Protect PUP surface). `INTERNET` is declared because Samsung WebView crashes on open without it; Chromium network fetches are intercepted 403.

## Magisk / root

No APK can grant `uid=0` without an existing root manager. Apex Care only requests Magisk Superuser when Magisk is already present; otherwise it uses userspace TEMP ROOT (non-uid0 cleanup helpers).

## Build logs

GitHub Actions logs for Publish / CI are deleted when the job finishes. Do not re-enable `jarsigner -verbose -certs` or keystore directory listings in workflows.
