# Maintenance playbook

Core premise stays fixed: **Make RAM Great Again** on Samsung One UI.

## Do

- Ship incremental versionCodes (`19`, `20`, …) with real fixes
- Keep Device Care free/used math (never invert free%)
- Keep protect-list + package validation in sync (Java + `index.html`)
- Run CI on `main` (assemble + signature + security greps)
- Tag `vX.Y.Z` only when you intend a user-facing APK
- Sample RAM with `sampleFast` on the UI / widget path (no `Thread.sleep`)
- First-open HW scan must use `scanUsableRamKbFast` on the UI thread
- WebView fallback is `loadDataWithBaseURL`, never `file://`

## Don't

- Re-release the same “Full Send” premise under a new marketing name
- Wipe all GitHub releases / tags from CI
- Pass unsanitized strings into `su -c`
- Re-enable WebView `AllowUniversalAccessFromFileURLs`
- Export custom widget actions as implicit broadcasts
- Commit non-demo production keystores or secrets
- Jump Android Gradle Plugin 8.x → 9.x in a security hotfix (validate on a dedicated branch)

## Release

```bash
# after merge to main
git tag v1.0.3
git push origin v1.0.3
# Publish Release workflow runs on the tag — it does not delete prior releases
```
