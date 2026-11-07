# Cloud

The Cloudflare Workers cloud (ADR 0004): a Worker in TypeScript with a D1 database. It serves data that is the same for every Viewer, and keeps an email address for each Account (below). Provider logins, stream URLs and Provider requests never come here (ADR 0001).

Deployed at `https://better-iptv-cloud.victor-trinh.workers.dev`; the app's `cloudUrl` (in `gradle.properties`) points there.

## API

Every endpoint but registration needs `Authorization: Bearer <device token>`, and answers `401` without a token a device was given.

- `POST /v1/devices` registers a device: `201 {"token": "…"}`. The app does this on first use and keeps the token in its database. The cloud keeps only the token's SHA-256.
- `POST /v1/metadata` takes `{"language": "fr", "titles": [{"kind": "movie", "tmdbId": 238}]}`, at most 50 Titles, and answers `{"titles": […], "notFound": […], "unavailable": […]}`. Each Title has its names in every language, its original language and title, alternative titles, descriptions in the requested language and English, genres in the requested language, popularity, release date, runtime, poster and backdrop paths (for `https://image.tmdb.org/t/p/<size><path>`), the logo path (the most voted PNG or SVG logo in the requested language, else in English, else null; an SVG's `.png` name gives a PNG of the size asked for), the most voted poster and backdrop with no language (`textlessPosterPath`, `textlessBackdropPath`: no text on them; null when TMDB has none), cast, IMDb ID and YouTube trailers in the requested language and English (`{key, language, official}`, official first). See `src/tmdb.ts`.
- `POST /v1/match` takes `{"listings": [{"kind": "movie", "name": "Titanic", "year": 1996, "language": "en", "tmdbId": null}]}`, at most 25 Listings by cleaned name (year, Likely language and the Provider's TMDB ID may be null), and answers `{"matches": [{"kind": "movie", "tmdbId": 597}]}` in the order asked. A Listing keeps its TMDB ID when its name is, or holds, one of TMDB's titles for it in any language; otherwise it is a Mismatch and, like a Listing without an ID, is looked up with TMDB's search in its language: the most popular Title of that kind named exactly so, released within a year of the Listing's year. `tmdbId` is null when there is none, and a match is null when TMDB couldn't answer right now. See `src/match.ts`.
- `POST /v1/search` takes `{"query": "der pate"}` and answers `{"titles": [{"kind": "movie", "tmdbId": 238, "popularity": 128.4}], "people": [{"name": "Al Pacino", "titles": [{"kind": "movie", "tmdbId": 238}]}]}`: what TMDB's multi search finds by any name in any language, and for the 3 most popular people found, every Title they acted in or directed (ADR 0009). The device keeps the Titles its Provider has. Answers are cached per normalized query for a day and credits per person for a week; `503` when TMDB can't search and the query was never searched. See `src/search.ts`.
- `POST /v1/subtitles` takes `{"kind": "movie", "tmdbId": 238, "season": null, "episode": null, "languages": ["fr"]}` (a Show's Episode gives its season and episode), 1 to 5 ISO 639-1 languages, and answers `{"subtitles": [{"id": "…", "language": "fr", "source": "opensubtitles", "release": "The.Godfather.1972.BluRay", "hearingImpaired": false, "downloads": 900}], "unavailable": []}`: the best 5 offers per language from OpenSubtitles and Subdl, searched by TMDB ID and, when the cloud has the Title's metadata, IMDb ID. OpenSubtitles' most downloaded come first, then Subdl's, and those for the hard of hearing after the rest; machine-translated ones are left out. `unavailable` names a service that couldn't answer. Searches are cached per Movie or Episode and language for a week (a day when nothing was found), but not while a service is down. `POST /v1/subtitles/download` takes `{"id": "…"}` from a search and answers `{"format": "srt", "text": "…"}` in UTF-8 (`srt`, `vtt` or `ass`; Subdl's zip is unpacked, and a season's zip gives the Episode's file), `404` when the service no longer has it, `503` when it can't give it now (OpenSubtitles' 20 downloads a day are used up). Every downloaded file is kept, so it is downloaded from a service only once. See `src/subtitles.ts`.
- `POST /v1/file-reads/lookup` takes `{"provider": "<SHA-256 of the Provider's host>", "listings": [123]}`, at most 50 movie Listing ids, and answers `{"files": [{"listingId": 123, "file": {…}}]}`: what a device Header read or played of those Listings' files (audio and subtitle Tracks, picture size, HDR format, bitrate), shared by every Viewer of that Provider. `POST /v1/file-reads` takes `{"provider": "…", "files": [{"listingId": 123, "file": {…}}]}` and keeps them, `204`. The cloud only ever sees the host's hash. See `src/files.ts`.
- `POST /v1/unknown-prefixes` takes `{"provider": "<SHA-256 of the Provider's host>", "prefixes": [{"prefix": "BR", "listingCount": 260, "example": "BR - Cold Pursuit (2019)"}]}`, at most 200, and keeps them with when each was first and last seen, `204`. A device sends these after a Sync finds Prefixes its Prefix table doesn't know. The Viewer never sees them; the weekly `unknown-prefixes` workflow lists the ones seen in the last 30 days in a GitHub issue, one section per Provider hash, so they get added to the Base table or that Provider's hand file. See `src/prefixes.ts`.
- `POST /v1/provider-table` takes `{"provider": "<SHA-256 of the Provider's host>"}` and answers that Provider's Provider table (ADR 0014) as `text/plain` in the Base table's format (the header of `core/src/main/resources/dev/betteriptv/core/prefix-table.txt` documents it), or an empty body for a Provider the cloud has never seen. Today it holds the Provider's hand lines; learned lines join later. A device asks at the start of every Sync and describes the Listings it lands with Base plus the answer; without an answer it keeps the last table it got. See `src/provider-tables.ts`.
- `POST /v1/charts` takes `{"region": "CA", "language": "fr"}` and answers the Charts: Netflix's Top 10 for the Region (`worldwide` when Netflix doesn't list it), TMDB's trending this week, and each TMDB genre's most popular Titles (Discover), named in the language. It reads only D1. A Cron Trigger (`triggers.crons` in `wrangler.jsonc`, each minute) reads TMDB and Netflix for the Regions and languages asked for in the last 30 days, a few parts a run, each saved as soon as it is read (#214). A Region or language asked for the first time is read within the next minutes; until then it gets the worldwide Top 10 and the genres named in English. Run the Cron Trigger by hand under `npm run dev` with `curl "http://localhost:8787/cdn-cgi/local/scheduled"`. See `src/charts.ts`.
- `POST /v1/games` takes `{"leagues": ["nhl", "premier-league"]}`, the Leagues a device has on (the ids in `src/schedule.ts`), and answers each League's Games for today and the next seven days: `{"leagues": [{"league": "nhl", "games": [{"id": "401892633", "league": "nhl", "start": "2026-11-04T00:00:00.000Z", "state": "upcoming", "home": {"id": "21", "name": "Toronto Maple Leafs", "shortName": "Maple Leafs", "abbreviation": "TOR", "aliases": ["Toronto Maple Leafs", "Maple Leafs", "TOR", "Toronto"], "logo": "https://…/tor.png", "color": "00205b", "alternateColor": "ffffff"}, "away": {…}, "broadcasters": ["ESPN+"]}]}]}`, by start. A team's `color` and `alternateColor` are ESPN's, six lowercase hex digits with no `#`, each left out when ESPN gives none (#349). `state` is `upcoming`, `live` or `over`; `broadcasters` names the national Broadcasters the schedule knows (US-market: Fox, CBS, ESPN+, Apple TV, never TSN). Never a score or a period. It reads only D1: the same Cron Trigger reads each League's schedule source (`src/schedule.ts`, ESPN's public scoreboard API for every League in M3, `src/espn.ts`, with no user agent since ESPN refuses a browser-like one) while some device asked for the League in the last 30 days, its current days every 5 minutes and its coming days hourly, 17 requests a run at most (`GAMES_SUBREQUESTS`) out of the Charts' budget, each day saved as it is read. A League asked for the first time is absent until the next run; one read with no Games this week has `[]`; while ESPN can't answer, the kept copy stands. Games that started over a day ago are dropped (ESPN's CFL endpoint answers out of season with a 2022 game). See `src/games.ts`.

TMDB's answers (including "no such Title") are cached in D1 per Title and language for a week (a day for "no such Title"), so a repeat request doesn't reach TMDB; an answer cached before textless backdrops were asked for is fetched again. When TMDB can't answer, the last cached answer is served, and Titles never fetched are listed as `unavailable`. The app also caches what it gets, for a week. Lookups by name are cached for 30 days (a day when TMDB found nothing); the app keeps match answers for 30 days (a week when TMDB found nothing).

Registration is open: a token identifies a device, so it can be revoked, but it doesn't stop someone from registering one. Registrations and sign-in codes are rate-limited per IP by a Cloudflare rule (Accounts, below).

## Accounts

A TV signs in to an Account from a phone, with no password (ADR 0015). The device token stays the credential: signing in binds it to the Account (`device.account_id`, `device.signed_in_at`), so every endpoint keeps answering the same token, and a device that never signs in is as before. See `src/accounts.ts` and `src/page.ts`.

- `POST /v1/sign-in` answers a fresh Sign-in code for the device, `{"code": "K7M2QX", "url": "https://zugig.app/sign-in?code=K7M2QX", "expiresIn": 600}`: 6 digits and capitals without 0, O, 1 and I, for 10 minutes, replacing the device's previous code. The TV shows the code and a QR of `url`.
  It may carry `{"model": "Fire TV Stick 4K Max"}`, kept to one line of at most 60 characters: the only time a device names its model, which the device list shows (`device.model`).
- `POST /v1/account` answers `{"email": "…"}`, the Account the device is signed in to, or `{"email": null}`. The TV polls it every 2 s while it shows the code.
- `POST /v1/account/devices` answers the devices signed in to this one's Account, by sign-in, `{"devices": [{"id": "3f9a0c1d2e4b5a69", "model": "Fire TV Stick 4K Max", "signedInAt": "2026-11-03T20:00:00.000Z", "thisDevice": true}]}`, or `[]` when it is signed in to none. The id is derived from the token's hash, from which it can't be turned back (#434).
- `POST /v1/account/sign-out`, with `{}` for this device or `{"device": "3f9a…"}` for another on its Account, clears that device's link (account, sign-in date and model), `204`; `404` when no such device is on the Account. The token stays registered.
- Every `/v1/` answer to a registered device carries `Zugig-Account: signed-in` or `none`, so a device signed out from another TV, or whose Account was deleted, goes anonymous at its next request, whatever it asked.
- `GET /sign-in` is the page the QR opens (the code filled in from `?code=`): the code, an email, a "Send me a link" button and the privacy note. No script, nothing loaded from elsewhere. `POST /sign-in` emails a magic link to the address when the code is live. Each IP may type 5 wrong codes in 10 minutes; the sixth try is refused, right or wrong, until they age out (kept by a hash of the IP). A code sends at most 5 links.
- The magic link, `GET /sign-in/link?token=…`, works once, for 15 minutes (even after its code expired). It opens a page with one "Sign in the TV" button, `POST /sign-in/link`, so a mail scanner that opens links (Outlook's Safe Links does) signs nobody in. The button binds the TV to the email's Account, creating it when the email is new. A used, unknown or expired link gets a plain message.
- "Delete my account", linked from the sign-in page: `GET /delete-account` asks for the address, and `POST /delete-account` emails a delete link when an Account uses it, answering the same when none does (and emailing nobody), so the page can't tell who has an Account. Each IP may ask 5 times and each address be sent 3 links in 15 minutes (`delete_request`, by hashes). The link, `GET /delete-account/link?token=…`, works once, for 15 minutes, and opens a page with one "Delete my account" button, `POST /delete-account/link`, which deletes the Account and everything it owns and unbinds every device signed in to it.
- The cloud keeps the email address (`account`), each signed-in device's model, the live codes, and only SHA-256 hashes of the magic tokens (`magic_link`, `delete_link`), of the IPs that typed wrong codes (`sign_in_miss`) and of who asked for delete links (`delete_request`). The Provider login never comes here.

### History sync

Where a Viewer stopped and what they Watched follow them between the Account's TVs (#435). See `src/sync.ts`.

- `POST /v1/sync` takes `{"cursor": 0, "provider": "<SHA-256 of the Provider's host>", "progress": [{"title": "show/1399", "season": 1, "episode": 2, "position": 600000, "duration": 3600000, "watched": false, "removed": false, "at": 1762500000000}]}`, at most 500 changes, and answers `{"cursor": 42, "reset": false, "progress": […]}`: the Account's changes stamped after the cursor, in the same shape, the device's own included. `403` when the device isn't signed in, which the device takes as signed out.
- A change is a Movie's (`season` and `episode` null) or an Episode's position, duration, Watched, or `removed` ("Remove from Continue watching", a tombstone), at `at` by the device's clock, held to the cloud's so a clock that runs ahead can't win every change after it. Per Movie or Episode the latest `at` wins, whichever arrives first, so of two TVs watching one Title the last to stop wins.
- `title` is the device's Title key. By TMDB ID (`movie/238`, `show/1399`) it is the same on every Provider; by Listing (`movie/listing/123`, an Unmatched Title or a Sports replay) it is kept under `provider` and only answered to a device on that Provider.
- The cursor is the Account's sync sequence number (`account.sync_seq`), bumped once per push and stamped on every row it changes, so a device offline for a week gets everything since its cursor. Tombstones are dropped 30 days after `at`; a device whose cursor is older than a dropped tombstone gets everything the Account has, marked `reset`, and forgets the history it pulled before that isn't in it. A device that just signed in asks from `0` and merges.
- #440 adds profiles, Favourites and settings as more sections of the same request and answer, stamped from the same sequence, so one cursor covers them.
- The device pushes after each stop and syncs on launch and on coming to the front; what it pulls lands between screens. A device that never signs in never calls it.

### Setting up the domain and the sender

The QR must point at the app's own domain, not `workers.dev`, which on a TV reads as phishing.

1. **Domain.** Add the domain (say `zugig.app`) to Cloudflare, then attach it to the Worker: Workers & Pages → `better-iptv-cloud` → Settings → Domains & Routes → Add → Custom domain → `zugig.app`. Then set `SITE_URL` (`https://zugig.app`) and `EMAIL_FROM` (`Zugig <sign-in@zugig.app>`) under `vars` in `wrangler.jsonc`. Until `SITE_URL` is set, the QR and the links name whatever address the request came to.
2. **Sender.** In Resend (free tier: 3,000 emails a month, 100 a day), Domains → Add domain → `zugig.app`. Resend lists the DNS records to add in Cloudflare's DNS, all DNS-only (grey cloud):
   - `TXT resend._domainkey` with the DKIM key Resend shows,
   - `MX send` → `feedback-smtp.<region>.amazonses.com`, priority 10 (the bounce path),
   - `TXT send` → `v=spf1 include:amazonses.com ~all` (SPF for the bounce path),
   - and, recommended, `TXT _dmarc` → `v=DMARC1; p=none;`.

   Once Resend says Verified, create an API key with Sending access for that domain and set it with `npx wrangler secret put RESEND_API_KEY`. Without it no email goes out, and the page says it couldn't send one.
3. **Rate limit.** Security → WAF → Rate limiting rules → Create rule, on the domain's zone (and on the `workers.dev` route turned off, Settings → Domains & Routes, so the rule can't be walked around):
   - If incoming requests match: `(http.request.method eq "POST" and http.request.uri.path in {"/v1/devices" "/v1/sign-in" "/sign-in" "/delete-account"})`
   - With the same characteristics: IP
   - When rate exceeds: 10 requests per 10 seconds (the free plan's period), then Block for 10 seconds.

   A TV asks one code per 10 minutes and a phone sends a link or two, so no household meets it; a script registering devices or guessing codes does. The per-IP wrong-code count above is the Worker's own second line.

Point the app at the domain by changing `cloudUrl` in `gradle.properties` once it serves the API.

## Secrets

- `TMDB_TOKEN`: TMDB's API read access token or its short API key (from themoviedb.org → Settings → API). It lives only here, never in the APK. Set it with `npx wrangler secret put TMDB_TOKEN`.
- `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`: an API consumer's key (opensubtitles.com → API consumers) and the free account its downloads count against; downloads without a login are capped per IP, and the Worker's IPs are shared. Without them, OpenSubtitles isn't asked.
- `SUBDL_API_KEY`: from subdl.com → account → API. Without it, Subdl isn't asked.
- `RESEND_API_KEY`: a Resend API key with Sending access for the app's domain (Accounts, above). Without it, no sign-in email goes out.

## Develop

```sh
npm install
npm test          # seam 2: the API in the Workers runtime (Miniflare), with TMDB and the email sender faked
npm run typecheck
npm run dev       # a local Worker on :8787; put TMDB_TOKEN=… (and the subtitle secrets, RESEND_API_KEY) in .dev.vars
```

Point a debug build at a local Worker with `./gradlew assembleDebug -PcloudUrl=http://<this Mac's IP>:8787`.

## Deploy

CI (`.github/workflows/cloud.yml`) tests every change under `cloud/`, and on `main` applies D1 migrations and deploys. It needs two repository secrets:

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token from the "Edit Cloudflare Workers" template, with D1 edit added.
- `CLOUDFLARE_ACCOUNT_ID`: the account ID `npx wrangler whoami` shows.

To deploy by hand: `npm run deploy`. Schema changes go in a new file under `migrations/`.

### Provider tables

Hand lines live in `provider-tables/`, one file per Provider named `<SHA-256 of its host, lowercase hex>.txt`, in the Base table's format; no Provider's host is written anywhere. `npm run deploy` (and so CI's deploy) loads them into D1's `provider_table` after the migrations, with `scripts/provider-tables.mjs`: each file's row is replaced in place, then the rows of files no longer in the repo are deleted, so a device asking mid-deploy never gets an empty table for a file that stays. `npm run provider-tables` loads them into a local Worker's D1 (`npm run dev`). Devices pick a change up at their next Sync. The Gradle tests check that every file parses as a Provider table, and the app still ships them as the table a device uses until the cloud has answered once.
