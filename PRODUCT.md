# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Android TV (10-foot UI, D-pad remote, no touch), built with Jetpack Compose for TV (`androidx.tv:tv-material`). The main device is an Amazon Fire TV Stick running Fire OS; the app is sideloaded.

## Users

The Viewer and their household, on the living-room TV with the Fire TV remote. Some household members did not set the app up, so every screen must make sense to someone who has never seen it. The Viewer is in Canada and browses in English; French is coming (#15), and household members may prefer it.

The job is picking something to watch from a huge IPTV catalog and starting it without fuss.

## Product Purpose

Replaces TiviMate for an Xtream Codes IPTV subscription. It turns 144k movie Listings, 36k show Listings and 45k live Listings in about 25 languages into a clean library: one card per Title, Versions grouped by language, and the Best playable Version started on Play. Success means the household opens this app instead of TiviMate, and nobody has to try copies one by one.

## Positioning

Every IPTV player shows the Provider's Listings as the Provider files them. This app shows Titles and is honest about what each Version holds: a Confirmed attribute was read from the file, a Likely one was inferred from its name. No other player draws that line.

## Operating Context

- Viewed from a couch at TV distance, often in a dim room, on a 1080p or 4K TV.
- Driven only by the Fire TV remote: D-pad, select, back, home, play/pause, rewind/fast-forward. Text entry is painful, so search must need as few keypresses as possible.
- The Fire TV Stick is a weak device; scrolling and focus moves must stay instant.
- The subscription allows one stream at a time: Header reads and Playback compete for it.

## Capabilities and Constraints

- Vocabulary is fixed by `CONTEXT.md` and used in the UI: Title, Movie, Show, Episode, Version, Backup, Confirmed, Likely, Track, Source, Provider, Sync, Content language, App language, Quality cap.
- App language is English or French; layouts must survive French strings being about 20% longer.
- The Provider login stays on the device, encrypted.
- No TV guide grid (ADR 0007).

## Brand Commitments

- The app's name on screen is **Zugig**. The Wordmark and the TV banner set it lowercase, "zugig", with a grey dot at its top right; sentences write Zugig.

## Evidence on Hand

- Real catalog numbers and examples are in the spec (#1) and `scripts/` survey output: Ballerina (2025) has 34 Listings; an untagged `NF` copy held 7 audio Tracks and 10 subtitle Tracks.
- No posters, artwork or TMDB metadata are in the app yet. Prototypes use placeholder content and must not pass it off as real.

## Product Principles

1. One Title, one card. Never show the Provider's duplication.
2. Say how sure we are. Confirmed and Likely look different everywhere they appear.
3. The remote is the only input. Every action is reachable with the D-pad, and focus is always obvious from the couch.
4. Never waste the one stream.
5. Fast on a weak stick beats rich on a strong one.

## Accessibility & Inclusion

- Legible from about 3 m: TV-safe text sizes and contrast, and focus visible without relying on colour alone.
- Bilingual interface (English, French).
- Audio-description Versions are labelled and never picked automatically.
