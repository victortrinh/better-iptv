# Install a debug build on a Fire TV Stick

The app is sideloaded (ADR 0006). During development it goes onto the stick over Wi-Fi with `adb`; the stick and the computer must be on the same network.

## Once, on the stick

1. Turn on developer options: **Settings → My Fire TV → About**, then press the select button on the device name (e.g. "Fire TV Stick 4K") seven times. Newer Fire OS versions hide Developer options until you do this.
2. **Settings → My Fire TV → Developer options → ADB debugging: On.**
3. Find the stick's IP address: **Settings → My Fire TV → About → Network**.

## Once, on the computer

Install the Android platform tools, which include `adb` (on a Mac: `brew install --cask android-platform-tools`, or use the copy in the Android SDK at `~/Library/Android/sdk/platform-tools`).

## Each install

Get an APK, either by building it:

```bash
./gradlew assembleDebug
```

which writes `app/build/outputs/apk/debug/app-debug.apk`, or by downloading the `better-iptv-debug-…` artifact from the latest run of the **Build** workflow on GitHub Actions (CI builds one on every push).

Connect to the stick (replace the address with yours):

```bash
adb connect 192.168.1.50:5555
```

The first time, the stick asks whether to allow USB debugging from this computer: choose **Always allow from this computer**, then run `adb connect` again.

Install, replacing any earlier build:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every build is signed with the debug key checked in at `app/debug.keystore`, so builds from CI and from any computer replace each other without uninstalling (which would delete the synced catalog and the saved login).

The app appears under **Your Apps & Channels**; start it from there, or with:

```bash
adb shell am start -n dev.betteriptv/dev.betteriptv.app.MainActivity
```

## Reading what Sync did

Sync logs counts and timings (never the login or stream locations) under the `Sync` tag:

```bash
adb logcat -s Sync
```
