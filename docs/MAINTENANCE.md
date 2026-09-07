# Maintenance playbook

Core premise stays fixed: **Make RAM Great Again** on Samsung One UI.

## Do

- Ship incremental versionCodes (`19`, `20`, ...) with real fixes
- Keep Device Care free/used math (never invert free%)
- Keep protect-list + package validation in sync (Java + `index.html`)
- Run CI on `main` (assemble + signature + security greps)
- Tag `vX.Y.Z` only when you intend the sole user-facing APK
- Sample RAM with `sampleFast` on the UI / widget path (no `Thread.sleep`)
- First-open HW scan must use `scanUsableRamKbFast` on the UI thread
- WebView fallback is `loadDataWithBaseURL`, never `file://`
- Bulk Optimize / widget Clean skip `IMPORTANCE_VISIBLE` and above
- Honor the user protect list (`USER_PREFS`) on every force-stop path
- Keep **one** GitHub Release (the current tag). Older tags/APKs are dropped
- Let Publish / CI **delete their own Actions logs** so signing detail does not stay public

## Don't

- Re-release the same Full Send premise under a new marketing name
- Leave older GitHub releases / tags published next to the current APK
- Dump `jarsigner -verbose -certs` or keystore listings into Actions logs
- Pass unsanitized strings into `su -c`
- Re-enable WebView `AllowUniversalAccessFromFileURLs`
- Export custom widget actions as implicit broadcasts
- Sweep all installed `com.samsung.android.app.*` packages from the widget
- `kill -9` by PID from the widget (PID reuse)
- Commit non-demo production keystores or secrets
- Jump Android Gradle Plugin 8.x to 9.x in a security hotfix (validate on a dedicated branch)

## Release

```bash
# after merge to main
git tag v1.0.4
git push origin v1.0.4
# Publish Release workflow:
#   - builds + publishes this tag as the sole GitHub Release
#   - deletes any other releases/tags
#   - deletes the workflow run logs when finished
```
