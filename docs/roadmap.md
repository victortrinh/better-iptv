# Roadmap

Settled 2026-10-24 in a design grilling session. Terms are defined in [CONTEXT.md](../CONTEXT.md); hard-to-reverse choices are in [docs/adr](adr/). Big decisions are made now for the whole roadmap; feature details are settled just before each milestone.

Priorities, in order: a fast catalog with the best Version in the Viewer's language, subtitles and smooth playback of every format on a nice interface; then live sports; then trending; then visual polish.

## Surveyed Provider (2026-10-24)

Re-run with `python3 -I scripts/survey.py` (reads `.env`, writes to the git-ignored `.survey/`).

- Connection limit: 1. On 2026-10-29 it wasn't enforced within one home network: a second stream from a Mac on the stick's network was served while the stick played, so the refusal code is still unknown ([#82](https://github.com/victortrinh/better-iptv/issues/82)). `ProviderLoadErrorPolicy` keeps retrying 403 and 429 in case it is one of those. A 407, which the Provider sent when a Channel's TS request was refused, reaches the player as a `ProtocolException` and is final, so the HLS Backup starts at once ([#108](https://github.com/victortrinh/better-iptv/issues/108)).
- 144,031 movie Listings (54,658 Titles), 36,252 show Listings (14,818 Titles), 45,710 live Listings; 22 MB compressed.
- TMDB ID on 94% of movie and show Listings.
- Names follow `PREFIX - Title (Year)` with 105 distinct Prefixes; Category names add hints ("MULTI-SUBS", "Dolby Vision", "DOLBY AUDIO", "VOSTFR").
- Movie details rarely list Tracks; Episode details list resolution and the first audio Track's language.
- 6,725 Event channels; the TV guide covers 15% of Channels.

## M0: Foundations

- One repo: app, cloud, scripts.
- Automatic APK builds; install on the stick over Wi-Fi with adb.
- Sync: stream the catalog into the device database.
- Prefix table for the surveyed Provider, covering all ~25 languages.
- Tests run against a snapshot of the real catalog.
- Measure Sync and Header read times on the stick itself.

### Measurements

| What | Where | Result |
|---|---|---|
| Sync of 150,000 generated movie Listings (~63 MB of JSON) from a local fake Provider, in a 128 MB heap | MacBook Pro (M-series), `SyncScaleTest` | 0.7 s; 1.2 s once Listings are Matched to Titles (#5); 2.2 s with suspected Mismatches found (#7), before the cloud is asked about them |
| Sync of the 148-Listing fixture | Android TV 14 emulator on the same Mac | 3.0 s |
| First Sync of the full real catalog (138,575 movie Listings, 58,120 Titles) | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, debug build | 129.4 s (#30; hours before it). The regroup by name then stopped after 385 of ~2,385 cloud batches when the Worker exceeded its CPU limit: tracked in [#32](https://github.com/victortrinh/better-iptv/issues/32). Details in [#23](https://github.com/victortrinh/better-iptv/issues/23). |
| Scrolling the movie list, holding Down | Same stick and build | Not smooth: every frame janky, median 150 ms, p99 2 s. Debug builds are slower; tracked in [#33](https://github.com/victortrinh/better-iptv/issues/33). |
| Scrolling the movie list: 150 Down presses back to back from one `input` process, which waits for each key to be handled, like Fire OS's 50 ms key repeat waits for the app | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, release-like `benchmark` build (R8, not debuggable; `./gradlew :app:assembleBenchmark`), 2026-10-27 | Before #33's fix: 8.3–9.3 s, median frame 46–53 ms, but only ~85 of the 150 presses moved focus a row; the rest were lost or moved it to another column, so each row took ~100 ms. Poster decoding ran a NativeAlloc GC every ~150 ms. After: every press moves one row in its column, in 6.7–7.0 s (45–47 ms a press, under the key repeat's 50 ms); 9.6 s for the first scroll after the app starts, while the JIT warms up. Frames are still slow (86% janky, median 48 ms, p99 ~120 ms). |
| Home: 30 Right, then 12 Down presses | Same stick and `benchmark` build, from a fresh start | 59% janky frames, median 28 ms, p90 250 ms (debug build: 52%, p90 105 ms). After #33's fix: 51–57%, p90 200–250 ms, 4.0–4.1 s for the 42 presses. |
| Browse frames after #63 (lean `TitleCard` and `FocusSurface`): 150 Down presses on Movies and Home's 30 Right then 12 Down, as above | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, `benchmark` build, 2026-10-29 | Movies, search index not built: warm 6.2–6.8 s → 5.2–5.8 s, median frame 48–53 → 34–38 ms; fresh start 11.6–13.0 → 8.8–10.4 s. The fresh-start slowness is the JIT warming up: once the libraries' Baseline Profiles are compiled (at install with the `.dm`, or by Fire OS's idle-time dexopt after ProfileInstaller writes the profile on first launch), a fresh start scrolls like a warm one, 5.5–5.8 s, median 38–40 ms. App rules added to the profile changed nothing. With the search index (#121) building, the fresh-start scroll took 16–18 s (old card: median 150 ms, 31–33 GCs, 16–17 s of GC); compiled and lean, 6.0–6.3 s, median 48 ms. Index built and held: warm 5.5–5.6 s, median 38–40 ms (each GC copies ~45 MB live instead of ~10: the stick's `heapmaxfree` is 2 MB, so a GC every ~6 MB allocated, and every ~2 MB of bitmaps). Home, fresh start, not compiled: 4.0–4.8 s → 3.9–4.4 s, median 65–93 → 53–73 ms; compiled: 2.3 s, median 21–23 ms. |
| Bigger type and posters ([#147](https://github.com/victortrinh/better-iptv/issues/147): posters 104 → 136 dp, Movies grid 7 → 5 columns): the same Movies and Home runs, before (`8ef15c4`) and after (`763120d`) | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, `benchmark` build installed with its `.dm`, 2026-10-30 ([#157](https://github.com/victortrinh/better-iptv/issues/157)) | No slower. Movies, fresh start with the search index building: 6.2–6.3 s → 5.5–5.8 s, median frame 48–53 → 34–38 ms, 15 → 13–14 GCs. Index built and held, warm: 5.5–5.7 s → 5.0–5.3 s, median 42 → 29–32 ms, p99 93–97 → 53–69 ms. Five bigger cards a row cost less than seven small ones, and the bigger bitmaps add no GCs. Home, fresh: 2.6–2.7 s → 2.4–2.5 s, median 22–26 → 22–31 ms; warm: 2.1 s → 2.0 s, median 19–21 → 13–22 ms. |
| Home rows like Netflix's ([#167](https://github.com/victortrinh/better-iptv/issues/167): focused card at the left margin, rows at a fixed height, one info panel): Home's 30 Right then 12 Down, before (`b193d57`) and after | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, `benchmark` build (compiled: with its `.dm`, or `cmd package compile -m speed-profile`), 2026-10-30 | No slower, though the rows now scroll on every press. Before: fresh 2.40–2.48 s, warm 1.6–2.2 s. After: fresh 2.48–2.49 s, warm 1.63–1.83 s. The first runs after installing either build took 4.7–5.2 s fresh, while the app did its first-launch work. |
| Browse frames on `main` after the bigger posters (#147) and Netflix-like Home rows (#168), for [#63](https://github.com/victortrinh/better-iptv/issues/63): Movies' 150 Down, Home's 30 Right then 12 Down | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, `benchmark` build of `a57d0e0` installed with its `.dm` through a `PackageInstaller` session (`adb install-multiple`, the updater's path; compiled `speed-profile`, `reason=install`), 2026-10-30 | Movies, first start right after that install, search index building: 5.66 s, median frame 38 ms, p99 105 ms, 11 GCs. Fresh starts after it, index building: 5.76–5.78 s, median 38 ms, p90 57–61 ms, p99 81 ms, 12 GCs. Index built and held, warm: 4.94–5.26 s, median 26–28 ms, p90 42 ms, p99 53 ms, 4–8 GCs. Every press keeps up (under the 50 ms key repeat). Home, fresh with the index building: 1.87–1.91 s, median 19 ms, p90 81–101 ms, 6 GCs; warm: 1.42–1.64 s, median 10–19 ms, p90 53–89 ms, 0–1 GCs. Each GC runs ~0.4–0.5 s on a background thread. |
| Search index in a few large primitive arrays instead of millions of Strings, arrays and map entries ([#63](https://github.com/victortrinh/better-iptv/issues/63) step 2): Movies' 150 Down and Home's 30 Right then 12 Down, as above, before (`a57d0e0`, the row above's runs) and after | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, `benchmark` build installed with its `.dm` through a `PackageInstaller` session, 2026-10-30 | Each GC on a background thread: ~0.4–0.5 s → 0.12–0.18 s; heap after a GC with the index held ~27 MB. Movies, first start after the install: 5.66 → 5.73 s, median 38 → 36 ms, GC time 4.6 → 3.4 s. Fresh, index building: 5.76–5.78 → 5.62–5.72 s, median 38 → 36 ms, p99 81 → 77–93 ms, GC time 4.6–4.7 → 3.0–3.4 s. Warm, index held: 4.94–5.26 → 4.81–5.64 s, median 26–28 → 26–28 ms, GC time 1.9–4.1 → 0.8–1.9 s. Home fresh: 1.87–1.91 → 1.83–1.88 s, median 19 → 17–18 ms, GC time 1.7–1.8 → 1.0 s; warm: 1.42–1.64 → 1.47–1.87 s, median 10–19 → 9–21 ms. On a Mac (`SearchIndexBenchmark`, a made-up catalog bigger than the real one): the index held 89 → 50 MB, the same results for 24 queries with typos, and queries ~1.8× faster. |
| Header read of one Version on its details page (MKV: one 256 KB range request; MP4: start, plus end when the index is there) | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, debug build, against the real Provider, 2026-10-27 | About 1.1 s per MKV and 2.5 s per MP4, one after another. The first read lands 5.5 s after the page opens, behind the Provider's movie details and the cloud lookup. Reads shared through the cloud come back all at once 1.5 s after the page opens, with no stream. Play pressed during a read starts in 6.1 s with no refused stream; the interrupted read is dropped. |
| Sync after the catalog is dropped by a schema change (138,644 movie and 36,302 show Listings) | Same stick and build, 2026-10-27 | 186 s, 472 Listings Matched by name. |
| Search index built from the whole catalog | Same stick and build | About 70–90 s of CPU on one core. Until #51's fix, every other catalog read (a details page, the grids) waited for it. |
| Starting a Version, and switching Version from the Menu key (#43) | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, debug build, against the real Provider, 2026-10-27 | Play to first frame: 3.8 s resuming an MKV at 7 min, 6.1 s for an MP4 while a Header read was running. Switching English (AAC) → Español (E-AC-3, passed through) → English: 3.5 s and 3.6 s from the old player's release to the new first frame, at the same scene, subtitles kept. Four 10 s seeks after: no refused stream. |
| Background reading's pace (#83) | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, debug build, against the real Provider, 2026-10-29 | Gentle: 20 reads in 10 min, one every 31.4 s. Normal: one every 7.2 s; 83 reads in 10.5 min, then a dead MP4 (HTTP 400, which Play gets too) stopped it for the day as if refused. Once dead files were passed over, Normal read past it with no refusal. Play during reading: 4.1 s (Gentle) and 4.2 s (Normal) from the key to the first frame, no refused stream. No reads while playing or with the app in the background; reading only runs while the app shows, so the Fire OS screensaver (5 min) stops it. |
| VLC's start after "Try other player" (Media3 let go → VLC's first picture), through the stream relay | Fire TV Stick 4K Max (AFTKA, `armeabi-v7a` libVLC), real Provider, Forgotten Love (2023) 1080p | 7.1 s on a Backup; 11.7 and 12.2 s on the Version's own file. That was over the 8 s start timeout, so VLC was cut off there and the next Backup played on Media3 instead. After [#58](https://github.com/victortrinh/better-iptv/issues/58)'s fix (through the relay, the 8 s count from the Provider's latest answer, up to 32 s), debug build, 2026-10-27: 13.9, 12.7 and 14.6 s on the Version's own file (to its first decoded frame), at the same scene each time. Media3 back from VLC: 2.9 s. The emulator took ~7 s (#36). |
| A dead Version giving way to its Backup ([#82](https://github.com/victortrinh/better-iptv/issues/82)); the dead Listing was a throwaway build pointing Speak (2004)'s first Version at an ID the Provider doesn't have | Fire TV Stick 4K Max (AFTKA), Fire OS 7.7.1.3, debug build, against the real Provider, 2026-10-29 | The Provider answers an unknown ID with HTTP 405 in 0.15 s, and a known ID with the wrong extension with 551 (from a Mac). On the stick: one 405, no retries, the Version failed 0.5 s after its player started, and the Backup's player started 0.8 s later (first frame 4 s after that). Seven seeks ahead on the Backup, two of them while the Mac held a second stream: no error, no switch. |

## M1: Replace TiviMate for Movies and Shows

- Design direction settled first, so the interface is good from the start.
- Home rows: Continue watching, New on your service, genres.
- Titles with Versions. Exact equivalents become Backups. Shows merged per Episode.
- Play picks the Best playable Version, using what the device reports about the stick and TV. The Viewer lowers it with the Quality cap in settings or the Versions switch in the player. Buffering suggests a lower Version but never switches by itself. A Backup is tried when a Version doesn't start within ~8 s (through VLC's relay, ~8 s without the Provider answering).
- Versions list: grouped by language, best quality first, Other languages collapsed. CAM copies last; Audio-description Versions never picked automatically.
- Badges: solid when Confirmed, outlined when Likely.
- Header reads: on the details page, plus Background reading (Off / Gentle / Normal, default Gentle). Bitrate from the Provider's movie details as a free quality signal.
- Search:
  - one search across Movies, Shows, Channels, Games and people
  - results after 2–3 letters on a remote-friendly keyboard
  - finds any language's title, ignores accents, tolerates typos
  - Content languages first, then popularity
- Subtitles: embedded first. Otherwise downloaded from OpenSubtitles or Subdl by IMDb/TMDB ID, cached in the cloud, with a timing adjustment.
- Player: Track picker, subtitle styling, audio and subtitle delay, resume, refresh-rate matching where the stick supports it, "try other player" (VLC).
- Settings:
  - Content languages: any language in the catalog, default the device's
  - App language: English and French; metadata follows it, falling back to English
  - Region: from the device, default Canada
  - Quality cap and Background reading pace
- `QC` Versions show as "Français (Québec)" under French.
- History stays on the device.
- Live: Favourites and search only; Clutter hidden.
- Cloud: TMDB lookups and cache, subtitle downloads, shared Header read results.
- In-app updater.
- Trailers from YouTube IDs, never through the Provider connection.

## M2: Live and sports

- Channel variants merged like Versions.
- Sports hub of live and upcoming Games. Matched from Event channel names and schedule broadcasters, with the TV guide as a second signal.
- Default leagues for Canada: NHL, NFL, NBA, MLB, CFL, MLS, Premier League. Pinned teams optional.
- Free schedule sources only, each swappable: ESPN's undocumented API, the NHL's and MLB's public feeds, OpenF1 or Jolpica, football-data.org.
- Sports replays move into the sports hub.

## M3: Trending

- Charts filtered to the Provider's Titles:
  - Netflix Top 10 for the Region (weekly)
  - TMDB trending (daily)
  - Trakt most watched (weekly)
- The Region can be switched from the Top 10 row title.

## M4: Polish

Animations, backdrops, trailers.

## M5: For everyone

- Accounts and history sync. Trakt sync as an option.
- Any Provider, with a Prefix table per Provider.
- Onboarding, profiles and a kids mode.
- Header read results shared between Viewers of the same Provider.
- Commercial licences (TMDB, Trakt) if the app is ever paid.

## Still to decide, before the milestone that needs it

- M1: design direction; how the Viewer enters the Provider login on the TV.
- M2: sports hub screens; rules for merging Channel variants.
- M3: row layout and refresh times.
- M5: account model, Prefix tables for other Providers, profiles.

## Accounts the Viewer sets up

- Before M1: Cloudflare, TMDB API key, OpenSubtitles API key.
- Before M3: Trakt API key.
