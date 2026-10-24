---
status: accepted
date: 2026-10-24
---

# Native Android TV app: Kotlin, Compose for TV and Media3

The app is written in Kotlin with Jetpack Compose for TV, and plays video with Media3 (ExoPlayer) plus its FFmpeg audio decoder and a libass subtitle renderer, falling back to the VLC engine for files Media3 can't open. The Viewer's top priorities are fast browsing and smooth playback on a Fire TV Stick with 1–2 GB of RAM, where native code and the stick's hardware decoders matter most. React Native would also reach Apple TV and phones, but needs more tuning for long lists on small sticks; Flutter has no Apple TV support. Using libVLC or mpv as the main player would cover odd formats in software, but they handle HDR, Dolby Vision and refresh-rate matching worse than Media3 on Fire OS.

## Consequences

- An Apple TV app would be a separate Swift app; the cloud and the domain model carry over.
- DTS and TrueHD audio, which Fire sticks can't decode, are decoded in software or passed through to a capable soundbar.
- Dual-layer Dolby Vision (profile 7) plays as HDR10 on Fire sticks.
