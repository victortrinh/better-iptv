# Releases and the in-app updater

The app updates itself (ADR 0006, ADR 0010). On every push to main, the **Release** workflow (`.github/workflows/release.yml`) runs the tests, builds the release APK signed with the release key, and publishes a GitHub release `build-<versionCode>` holding:

- `better-iptv-0.1.<versionCode>.apk`
- `better-iptv-0.1.<versionCode>.dm`: the build's Baseline Profile as dex metadata, in Android 9–11's format (#63)
- `update.json`: `{"versionCode": 95, "versionName": "0.1.95", "apk": "https://github.com/victortrinh/better-iptv/releases/download/build-95/better-iptv-0.1.95.apk", "sha256": "…", "size": 61234567, "dm": "https://…/better-iptv-0.1.95.dm", "dmSha256": "…", "dmSize": 6419}`

Release builds read `https://github.com/victortrinh/better-iptv/releases/latest/download/update.json` on launch, and again whenever the app comes back to the front after fifteen minutes or more (`updateUrl` in `gradle.properties`); a stick keeps the process alive for days, so the launch check alone would miss every build since. When it names a higher versionCode, the app shows "Update available" before anything else. "Update now" downloads the APK and the `.dm`, checks their sizes and SHA-256 against `update.json`, and hands both to Android's installer in an install session, which asks the Viewer to confirm. On Android 9–11 (Fire OS 7 and 8) the `.dm` has Android compile the app at install, so the first scroll after an update keeps up instead of waiting for Fire OS's idle-time compile; a `.dm` that doesn't download or match is left out and the APK installs alone. "Later" puts it away until the next launch or a newer build, and Settings → App version offers it too. The first time, Android has to allow the app to install apps, and the app walks the Viewer there.

Confirming the install closes the app: Android force-stops it as the install starts, and again when the install finishes. On the stick that is 15–20 s later, Fire OS compiling the build from its `.dm` in between, and Fire TV's Home shows meanwhile. The app then reopens by itself (`UpdateReopenReceiver`, on `MY_PACKAGE_REPLACED`, when the app committed the install; an `adb install` leaves it closed), and the Installing screen says so. Opening it from Home before the install finishes still gets it closed at the second stop, and reopened right after; it shows "Finishing the update" meanwhile instead of Home or the update offer (#452). It knows from the mark it set at commit and its install session, which Android lists until the install finishes, at 0.8 progress while the installer waits for Install and 0.9 once installing. Between the download and Android's installer, which takes seconds on the stick while the build is copied into the session, the screen shows the waiting dot and no button. Android 10 and later don't let a receiver reopen an app, so there the Viewer opens it themselves. Measured on Fire OS 7, 2026-11-06 ([#418](https://github.com/victortrinh/better-iptv/issues/418)).

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

## Once: installing the release app on the stick

The release app, `dev.betteriptv`, is a separate package from the debug app, `dev.betteriptv.debug`, so it installs beside it: nothing needs uninstalling, and the debug app keeps its catalog and login. The release app keeps its own, so you log in to it separately.

1. Download the APK from the latest release on GitHub and install it over adb (see `install-on-fire-tv.md` for connecting):

   ```bash
   adb install better-iptv-0.1.<versionCode>.apk
   ```

2. Start Zugig from **Your Apps & Channels** and log in. From then on the app updates itself.

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the stick has an old debug-signed build under `dev.betteriptv`, from before debug builds became their own package. Uninstall that one first (`adb uninstall dev.betteriptv`, which deletes its catalog and login), then install again.

### A stale icon in Your Apps

If **Your Apps & Games** shows an old icon on Zugig's tile (a navy disc, a cloud, a lone z or an earlier wordmark), the Appstore is drawing its own copy of the icon, not the installed one. It saves that copy when a package is first installed, as `/data/securedStorageLocation/com.amazon.venezia/<package>`, and deletes it only when the package is removed. Updates keep the old copy, whether they come from the app's updater or `adb install -r`, and so do reboots. On top of that the Appstore caches the picture on disk and in memory, so clearing the Appstore's cache alone just reloads the old copy.

Zugig replaces the copy itself after each update (`AppstoreTileReceiver`, on `MY_PACKAGE_REPLACED`). It tells the Appstore's `AppManagerBroadcastReceiver` that the package was removed, then added, and the Appstore deletes the old copy and saves the installed icon. The tile keeps its place. The Viewer still has to clear the cached picture: in **Settings › Applications › Manage Installed Applications › Appstore**, choose **Force stop**, then **Clear cache** (not Clear data), and open Your Apps again. This was measured on Fire OS 7 with Appstore 8.5103. It relies on an undocumented Appstore receiver, so if a later Appstore drops it, use the adb recipe below.

To redraw the tile with adb, from the installed APK, and keep the app's login and catalog:

1. Pull the installed APK, remove the package but keep its data, and install it again:

   ```bash
   adb pull "$(adb shell pm path dev.betteriptv | sed 's/^package://')" zugig.apk
   ```

   ```bash
   adb shell pm uninstall -k dev.betteriptv
   ```

   ```bash
   adb install zugig.apk
   ```

   `-k` keeps the app's files and its Keystore key, so the login still opens. The uninstall deletes the Appstore's copy and the install saves the current icon.

2. In **Settings › Applications › Manage Installed Applications › Appstore**, choose **Force stop**, then **Clear cache** (not Clear data). You need both: Clear cache leaves the old picture in the running Appstore's memory, and Force stop leaves it on disk.

3. Open Your Apps again.

The debug app is the same with `dev.betteriptv.debug`. Never use a plain `adb uninstall` for this: it deletes the login and the catalog. The tile keeps its place in Your Apps.

Every stick that already has Zugig hits this when the icon changes, because the updater's installs are updates too. Once a build with `AppstoreTileReceiver` is installed, the next update replaces the Appstore's copy. The Viewer still needs Force stop and Clear cache to see it, so treat a new launcher icon as costly for existing installs anyway.

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

## The Baseline Profile and the browse benchmarks

The `.dm` holds the libraries' Baseline Profiles and the app's own rules, `app/src/main/generated/baselineProfiles/baseline-prof.txt`: app start to Home's rows, Right along a row, Down through the rows, and the Movies tab's rows (#63). The `:baselineprofile` module generates them and holds Macrobenchmarks for the runs in the roadmap's Measurements table. Both run on a device or emulator, never in CI.

They drive the debug app, `dev.betteriptv.debug`, which the `nonMinifiedRelease` and `benchmarkRelease` builds install over: log it in, let it sync, set it to English and leave it on Home first. Each run starts from the first row's first card, a Down below the featured hero the screen opens on. Nothing presses OK on a card or on the hero, so no stream opens. The app stays installed when a run ends (`android.injected.androidTest.leaveApksInstalledAfterRun` in `gradle.properties`): without it, AGP uninstalls `dev.betteriptv.debug` afterwards, and its login and catalog with it. With more than one device connected, set `ANDROID_SERIAL`.

- Regenerate the rules (Android 13 or later, or a rooted device; the stick's Fire OS 7 can't), then commit the file:

  ```bash
  ./gradlew :app:generateBaselineProfile
  ```

- Frame timings of Home's 30 Right then 12 Down and the Movies tab's 150 Down, fresh and warm, uncompiled and with the profile compiled:

  ```bash
  ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
  ```

On the Android TV emulator, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`, and force-stop Google Play services and the keyboard first (`adb shell am force-stop com.google.android.gms`, then `com.google.android.inputmethod.latin`): their tracing doesn't answer Perfetto's last flush, and the benchmark fails with "Failed to stop perfetto". Emulator numbers aren't the stick's.
