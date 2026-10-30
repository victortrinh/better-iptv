# Cloud

The Cloudflare Workers cloud (ADR 0004): a Worker in TypeScript with a D1 database. It serves only data that is the same for every Viewer. Provider logins, stream URLs and Provider requests never come here (ADR 0001).

Deployed at `https://better-iptv-cloud.victor-trinh.workers.dev`; the app's `cloudUrl` (in `gradle.properties`) points there.

## API

Every endpoint but registration needs `Authorization: Bearer <device token>`, and answers `401` without a token a device was given.

- `POST /v1/devices` registers a device: `201 {"token": "…"}`. The app does this on first use and keeps the token in its database. The cloud keeps only the token's SHA-256.
- `POST /v1/metadata` takes `{"language": "fr", "titles": [{"kind": "movie", "tmdbId": 238}]}`, at most 50 Titles, and answers `{"titles": […], "notFound": […], "unavailable": […]}`. Each Title has its names in every language, its original language and title, alternative titles, descriptions in the requested language and English, genres in the requested language, popularity, release date, runtime, poster and backdrop paths (for `https://image.tmdb.org/t/p/<size><path>`), the logo path (the most voted PNG or SVG logo in the requested language, else in English, else null; an SVG's `.png` name gives a PNG of the size asked for), cast, IMDb ID and YouTube trailer IDs. See `src/tmdb.ts`.
- `POST /v1/match` takes `{"listings": [{"kind": "movie", "name": "Titanic", "year": 1996, "language": "en", "tmdbId": null}]}`, at most 25 Listings by cleaned name (year, Likely language and the Provider's TMDB ID may be null), and answers `{"matches": [{"kind": "movie", "tmdbId": 597}]}` in the order asked. A Listing keeps its TMDB ID when its name is, or holds, one of TMDB's titles for it in any language; otherwise it is a Mismatch and, like a Listing without an ID, is looked up with TMDB's search in its language: the most popular Title of that kind named exactly so, released within a year of the Listing's year. `tmdbId` is null when there is none, and a match is null when TMDB couldn't answer right now. See `src/match.ts`.
- `POST /v1/search` takes `{"query": "der pate"}` and answers `{"titles": [{"kind": "movie", "tmdbId": 238, "popularity": 128.4}], "people": [{"name": "Al Pacino", "titles": [{"kind": "movie", "tmdbId": 238}]}]}`: what TMDB's multi search finds by any name in any language, and for the 3 most popular people found, every Title they acted in or directed (ADR 0009). The device keeps the Titles its Provider has. Answers are cached per normalized query for a day and credits per person for a week; `503` when TMDB can't search and the query was never searched. See `src/search.ts`.
- `POST /v1/subtitles` takes `{"kind": "movie", "tmdbId": 238, "season": null, "episode": null, "languages": ["fr"]}` (a Show's Episode gives its season and episode), 1 to 5 ISO 639-1 languages, and answers `{"subtitles": [{"id": "…", "language": "fr", "source": "opensubtitles", "release": "The.Godfather.1972.BluRay", "hearingImpaired": false, "downloads": 900}], "unavailable": []}`: the best 5 offers per language from OpenSubtitles and Subdl, searched by TMDB ID and, when the cloud has the Title's metadata, IMDb ID. OpenSubtitles' most downloaded come first, then Subdl's, and those for the hard of hearing after the rest; machine-translated ones are left out. `unavailable` names a service that couldn't answer. Searches are cached per Movie or Episode and language for a week (a day when nothing was found), but not while a service is down. `POST /v1/subtitles/download` takes `{"id": "…"}` from a search and answers `{"format": "srt", "text": "…"}` in UTF-8 (`srt`, `vtt` or `ass`; Subdl's zip is unpacked, and a season's zip gives the Episode's file), `404` when the service no longer has it, `503` when it can't give it now (OpenSubtitles' 20 downloads a day are used up). Every downloaded file is kept, so it is downloaded from a service only once. See `src/subtitles.ts`.
- `POST /v1/file-reads/lookup` takes `{"provider": "<SHA-256 of the Provider's host>", "listings": [123]}`, at most 50 movie Listing ids, and answers `{"files": [{"listingId": 123, "file": {…}}]}`: what a device Header read or played of those Listings' files (audio and subtitle Tracks, picture size, HDR format, bitrate), shared by every Viewer of that Provider. `POST /v1/file-reads` takes `{"provider": "…", "files": [{"listingId": 123, "file": {…}}]}` and keeps them, `204`. The cloud only ever sees the host's hash. See `src/files.ts`.

TMDB's answers (including "no such Title") are cached in D1 per Title and language for a week (a day for "no such Title"), so a repeat request doesn't reach TMDB; an answer cached before logos were asked for is fetched again. When TMDB can't answer, the last cached answer is served, and Titles never fetched are listed as `unavailable`. The app also caches what it gets, for a week. Lookups by name are cached for 30 days (a day when TMDB found nothing); the app keeps match answers for 30 days (a week when TMDB found nothing).

Registration is open: a token identifies a device, so it can be revoked, but it doesn't stop someone from registering one. That's acceptable while the cloud only serves public TMDB data at personal scale.

## Secrets

- `TMDB_TOKEN`: TMDB's API read access token or its short API key (from themoviedb.org → Settings → API). It lives only here, never in the APK. Set it with `npx wrangler secret put TMDB_TOKEN`.
- `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`: an API consumer's key (opensubtitles.com → API consumers) and the free account its downloads count against; downloads without a login are capped per IP, and the Worker's IPs are shared. Without them, OpenSubtitles isn't asked.
- `SUBDL_API_KEY`: from subdl.com → account → API. Without it, Subdl isn't asked.

## Develop

```sh
npm install
npm test          # seam 2: the API in the Workers runtime (Miniflare), with TMDB faked
npm run typecheck
npm run dev       # a local Worker on :8787; put TMDB_TOKEN=… (and the subtitle secrets) in .dev.vars
```

Point a debug build at a local Worker with `./gradlew assembleDebug -PcloudUrl=http://<this Mac's IP>:8787`.

## Deploy

CI (`.github/workflows/cloud.yml`) tests every change under `cloud/`, and on `main` applies D1 migrations and deploys. It needs two repository secrets:

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token from the "Edit Cloudflare Workers" template, with D1 edit added.
- `CLOUDFLARE_ACCOUNT_ID`: the account ID `npx wrangler whoami` shows.

To deploy by hand: `npm run deploy`. Schema changes go in a new file under `migrations/`.
