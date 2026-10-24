---
status: accepted
date: 2026-10-24
---

# Distributed as a sideloaded APK with an in-app updater, not through app stores

Google Play and the Amazon Appstore often reject IPTV clients, so the app is installed directly: over Wi-Fi with adb while it's being built, then through an in-app updater that downloads new versions from the cloud once the Viewer has allowed "install unknown apps". This limits the app to devices that allow sideloading: Fire OS sticks, Google TV and Android TV. Amazon's Linux-based Vega OS sticks only accept store apps and are out of reach.
