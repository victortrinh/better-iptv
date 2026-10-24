---
status: accepted
date: 2026-10-24
---

# Versions are Header read before playback, despite a one-stream Connection limit

Prefixes undersell Versions: in a test, an Untagged `NF` Version turned out to hold 7 audio and 10 subtitle Tracks, including English, which no Prefix or TMDB original language would have revealed. So the app Header reads Versions to Confirm their Tracks, resolution and bitrate: on a Title's details page (the best unconfirmed Version per language), and through gentle Background reading of what's on the home screen, Continue watching and new arrivals. Reads only happen while nothing is playing, because the surveyed subscription allows a single stream. The test showed a read costs about 1 s for MKV (78% of Movies) and about 2 s for MP4, the Provider supports partial downloads, and it frees the connection within 0.2 s, so pressing Play right after a read works. We first ruled file reading out as too slow; the test reversed that.

## Consequences

- Many reads in a row could trip a Provider's flood protection, so Background reading is slow, has an Off / Gentle / Normal setting, and stops for the day at the first sign of pushback.
- Results are kept forever, and once the app serves several Viewers, those on the same Provider share them.
- Until Confirmed, attributes show as Likely, with a distinct badge style.
