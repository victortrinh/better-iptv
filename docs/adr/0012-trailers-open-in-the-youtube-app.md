---
status: accepted
date: 2026-11-03
---

# Trailers open in the YouTube app, not in Zugig's player

TMDB lists each Title's trailers as YouTube videos, and the cloud already reads their ids. A Title page's "Trailer" row hands the video to the device's YouTube app (on the Fire TV Stick, `com.amazon.firetv.youtube`), and Back from YouTube returns to the page. The row is left out when the Title has no trailer or the device has no YouTube app.

## Considered Options

- **Play trailers in Zugig's own player through a YouTube stream extractor** (NewPipe's or similar): rejected. It breaks whenever YouTube changes its pages, needs an app update each time, and goes against YouTube's terms.
- **Muted trailers playing by themselves on details or under a focused card**: rejected, as with Netflix's previews (`docs/design/netflix-study.md`). It costs the weak stick a video decoder while browsing, and breaks "Artwork stays still".

## Consequences

- Watching a trailer leaves the app for a moment. Zugig's player settings (subtitle style, delays) don't apply to it.
- Trailers never touch the Provider, so they don't compete for the one connection.
