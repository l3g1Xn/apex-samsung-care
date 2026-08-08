# Security Policy — Apex Care

**Make RAM Great Again** · `com.apexcare.app`

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.0.1+  | Yes — maintenance line |
| 1.0.0   | Superseded — upgrade recommended |
| < 1.0.0 | No |

## What this app does (threat model)

- Reads on-device RAM/storage metrics (`/proc/meminfo`, `ActivityManager`)
- Lists running packages and may force-stop **non-protected** apps
- Optional Magisk `su` for real `am force-stop` when the user grants Superuser
- UI is a **local** WebView asset (network loads blocked)

It does **not** phone home, upload package lists, or auto-root a stock device.

## Protections (1.0.1)

- **Package name validation** before any kill / force-stop path
- **Root command allowlist** (`id`, `am force-stop <pkg>`, `cmd activity force-stop <pkg>`, `kill -9 <pid>`)
- **Expanded protect list** (One UI telephony, input, Knox, Magisk, launcher, GMS, …)
- **WebView locked down**: no universal file access, no network loads, no mixed content
- **Backup disabled** for app data (`allowBackup=false`, data extraction rules exclude prefs)
- **Cleartext traffic disabled**

## Reporting a vulnerability

1. Open a private security advisory on GitHub if available, **or**
2. File an issue titled `[SECURITY]` with reproduction steps (no exploit chains in public if critical).

Please do **not** open PRs that demonstrate live RCE payloads against end-user devices.

## Sideload signing

Release APKs use a **demo/sideload keystore** committed for CI reproducibility.  
Treat it as public. For distribution you control, generate your own keystore and use `android/keystore.properties` (gitignored).

## Magisk / root

No APK can grant `uid=0` without an existing root manager. Apex Care only requests Magisk Superuser when Magisk is already present; otherwise it uses userspace TEMP ROOT (non-uid0 cleanup helpers).
