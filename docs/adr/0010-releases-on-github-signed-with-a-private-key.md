---
status: accepted
date: 2026-10-28
---

# Releases are published on GitHub on every push to main, signed with a key only the Viewer holds

The in-app updater (ADR 0006) needs somewhere to download builds from and a way to tell a newer build from an older one.

- **Where:** a GitHub release per build, holding the APK and `update.json`. The repository is public, so the app reads `releases/latest/download/update.json` without a token, and CI publishes with its own `GITHUB_TOKEN`. Serving them from the Worker through an R2 bucket would mean a bucket, R2 rights on the Cloudflare token and a new endpoint, with nothing gained for one household.
- **When and which number:** every push to main that passes the tests is a release. Its versionCode is the number of commits on main, so it always goes up and a local build of the same commit gets the same number; its versionName is `0.1.<versionCode>`.
- **Signed with:** a release key the Viewer generated and gave CI as secrets. The debug key is checked in and public, so with it Android would accept anyone's APK as an update, and `update.json`'s SHA-256 would be the only check.

## Consequences

- The release app can't install over a build signed with the debug key. Moving the stick to it costs one uninstall, which deletes the synced catalog and the saved login.
- Debug builds are a separate app, `dev.betteriptv.debug`, so an adb install sits beside the release instead of clashing with it. Benchmark builds use the same id.
- If the release key is lost, the next build can't update the installed app: the Viewer uninstalls once more and CI gets a new key.
