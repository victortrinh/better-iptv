# Install on a Google TV

The APK that runs on the Fire TV Stick runs on Google TV and other Android TV devices (a Chromecast with Google TV, the Google TV Streamer, an NVIDIA Shield, a TV with Google TV built in) that run Android 7.1 or later on an ARM processor. Nothing is built differently: the manifest already asks for the TV launcher and no touchscreen. The app is sideloaded here too (ADR 0006), and the release updates itself (`releases.md`).

Not checked on a real Google TV yet: the differences below come from the code and from Android's documentation, and the first install on one should confirm them.

## Once, on the device

1. Turn on developer options: **Settings → System → About**, then press OK on **Android TV OS build** seven times.
2. **Settings → System → Developer options → USB debugging: On.** It takes `adb` over Wi-Fi, as the stick does.
3. Find its IP address: **Settings → Network & Internet**, then the connected network.

## Each install

As on the stick (`install-on-fire-tv.md`): `adb connect <address>:5555`, accept the prompt on the TV, then `adb install -r` the APK, the debug build from `./gradlew assembleDebug` or CI, or the release from GitHub.

Without a computer, put the release APK where the device can open it (a USB stick, or a download in a file manager app from Play) and allow that app under **Settings → Apps → Security & restrictions → Unknown sources**.

Zugig appears in **Apps → Your apps** with its banner.

## What differs from the Fire TV Stick

- **The remote has no menu key**, and most have no play/pause, rewind or fast-forward. Holding OK on a Channel adds or removes the Favourite, and the hints say "Hold OK" instead of "≡". In the player, OK brings up the overlay, whose buttons pause, jump and open the Versions (a Game's Feeds); Left and Right scrub.
- **Updates.** The first update asks to allow Zugig to install apps; the switch is under **Settings → Apps → Security & restrictions → Unknown sources**. These devices run Android 10 or later, which doesn't let an app reopen itself after an update, so open Zugig again from the launcher.
- **The Appstore tile refresh** (`AppstoreTileReceiver`) does nothing here: there is no Amazon Appstore.
- **Trailers** that can't be embedded open in Google's YouTube app for TV.
- **Media3 reads the Provider through OkHttp**, on every device since. On a TV with Google TV, Media3 on Android's own HTTP stack failed to start every Version, which VLC then played through the relay (OkHttp). Not confirmed on that TV yet: if Media3 still fails there, `adb logcat -s Player` names why it stopped.
- **Speed and formats** were measured on the stick only (`playback-samples.md`). What plays is read from the device at each Play (`readDeviceProfile`), so a device that decodes more is offered more.
