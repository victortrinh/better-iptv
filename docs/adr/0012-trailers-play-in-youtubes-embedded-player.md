---
status: accepted
date: 2026-11-03
updated: 2026-11-06
---

# Trailers play in YouTube's embedded player, inside Zugig

TMDB lists each Title's trailers as YouTube videos, and the cloud already reads their ids. A Title page's "Trailer" row opens the Trailer screen: YouTube's IFrame player in a WebView, full screen, starting by itself. Back returns to the page with focus on Trailer, and so does the trailer ending. A trailer whose owner won't let others embed it (player errors 101, 150 and 153) opens in the device's YouTube app instead (on the Fire TV Stick, `com.amazon.firetv.youtube`). The row is left out only when the Title has no trailer.

## Why not the YouTube app first (2026-11-06, #385)

The first version of this decision opened every trailer in the YouTube app. On the stick, Back from it landed on YouTube's home, never on Zugig, and leaving YouTube took three or four more presses. Fire TV's YouTube (version 24.40, decompiled) runs in a `singleTask` activity, so intent flags don't change where Back goes. It also rewrites every link it gets into a YouTube TV web URL (`/tv?launch=launcher`, `dial`, `promo`…), and YouTube TV's web app always puts its home under a deep-linked video. We tried the web link, the DIAL form, a pass-through `/tv` link and `inApp`, each from a cold and a warm start. All landed on home.

## Considered Options

- **The YouTube app for every trailer**: the first version, replaced as above. It stays as the fallback for trailers that can't be embedded.
- **Play trailers in Zugig's own player through a YouTube stream extractor** (NewPipe's or similar): rejected. It breaks whenever YouTube changes its pages, needs an app update each time, and goes against YouTube's terms. The IFrame player is YouTube's own way to embed videos.
- **Muted trailers playing by themselves on details or under a focused card**: rejected, as with Netflix's previews (`docs/design/netflix-study.md`). It costs the weak stick a video decoder while browsing, and breaks "Artwork stays still".

## Consequences

- The trailer plays in YouTube's player, not Zugig's: the player's settings (subtitle style, delays) don't apply, and YouTube draws its own captions and controls. The remote's OK, Left and Right go to the player through its JavaScript API.
- The WebView sits over the app's Compose content rather than inside it: inside Compose, Fire OS 7's WebView plays the sound but draws no picture.
- On the stick a trailer starts about 4.5 s after OK, and the first one in a session freezes the screen for about 1.3 s while the WebView loads. Trailers never touch the Provider, so they don't compete for the one connection.
- A trailer that can't be embedded still has the YouTube app's Back problem.
