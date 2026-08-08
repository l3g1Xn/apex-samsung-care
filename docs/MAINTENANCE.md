# Maintenance playbook

Core premise stays fixed: **Make RAM Great Again** on Samsung One UI.

## Do

- Ship incremental versionCodes (`19`, `20`, …) with real fixes
- Keep Device Care free/used math (never invert free%)
- Keep protect-list + package validation in sync (Java + `index.html`)
- Run CI on `main` (assemble + signature + security greps)
- Tag `vX.Y.Z` only when you intend a user-facing APK

## Don't

- Re-release the same “Full Send” premise under a new marketing name
- Wipe all GitHub releases / tags from CI
- Pass unsanitized strings into `su -c`
- Re-enable WebView `AllowUniversalAccessFromFileURLs`
- Commit non-demo production keystores or secrets

## Release

```bash
# after merge to main
git tag v1.0.1
git push origin v1.0.1
# Publish Release workflow runs on the tag
```
