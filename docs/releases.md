# Releases and the in-app updater

The app updates itself (ADR 0006, ADR 0010). On every push to main, the **Release** workflow (`.github/workflows/release.yml`) runs the tests, builds the release APK signed with the release key, and publishes a GitHub release `build-<versionCode>` holding:

- `better-iptv-0.1.<versionCode>.apk`
- `update.json`: `{"versionCode": 95, "versionName": "0.1.95", "apk": "better-iptv-0.1.95.apk", "sha256": "…", "size": 61234567}`

Release builds read `https://github.com/victortrinh/better-iptv/releases/latest/download/update.json` on launch (`updateUrl` in `gradle.properties`). When it names a higher versionCode, the app shows "Update available" before anything else. "Update now" downloads the APK, checks its size and SHA-256 against `update.json`, and opens Android's installer; "Later" puts it away until the next launch, and Settings → App version offers it too. The first time, Android has to allow the app to install apps, and the app walks the Viewer there.

Debug and benchmark builds don't check. They are a separate app, `dev.betteriptv.debug` ("Zugig debug"), signed with the public debug key.

## Once: the release key

Only you hold this key. CI signs with it, and Android only accepts an update signed with the same key as the installed app, so **keep a backup of the keystore and its passwords** (in a password manager, for example). If it's lost, the next update means uninstalling the app on the stick.

1. Generate the keystore on your computer, outside the repository:

   ```bash
   keytool -genkeypair -keystore ~/better-iptv-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Zugig"
   ```

   keytool asks for a password; use the same one for the key when it asks.

2. Add four repository secrets (GitHub → Settings → Secrets and variables → Actions → New repository secret):

   - `RELEASE_KEYSTORE_BASE64`: the keystore as base64, from `base64 -i ~/better-iptv-release.jks | pbcopy`
   - `RELEASE_KEYSTORE_PASSWORD`: the password
   - `RELEASE_KEY_ALIAS`: `release`
   - `RELEASE_KEY_PASSWORD`: the password again

Until the secrets exist, the Release workflow skips with a warning and publishes nothing.

## Once: moving the stick to the release app

The app on the stick today is signed with the debug key, so the first release can't install over it.

1. Uninstall it: **Settings → Applications → Manage Installed Applications → Zugig → Uninstall** (or `adb uninstall dev.betteriptv`). This deletes the synced catalog and the saved login.
2. Download the APK from the latest release on GitHub and install it over adb (see `install-on-fire-tv.md` for connecting):

   ```bash
   adb install better-iptv-0.1.<versionCode>.apk
   ```

3. Log in again. From then on the app updates itself.

## Building a release locally

```bash
RELEASE_KEYSTORE=~/better-iptv-release.jks RELEASE_KEYSTORE_PASSWORD=… RELEASE_KEY_ALIAS=release RELEASE_KEY_PASSWORD=… ./gradlew assembleRelease
```

Without `RELEASE_KEYSTORE`, `assembleRelease` makes an unsigned APK, which Android won't install.

## Testing the updater against your own manifest

Build two release builds signed with any test key, pointed at a local manifest (`10.0.2.2` is the host from the Android emulator), install the older one, and serve the newer one with an `update.json` beside it:

```bash
./gradlew assembleRelease -PupdateUrl=http://10.0.2.2:8765/update.json
```

The versionCode is the commit count, so build N, commit, then build N+1. `AppUpdatesTest` covers the check, the first-update permission, the download's checks and the installer with fakes.
