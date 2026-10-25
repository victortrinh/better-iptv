# Cloud

The Cloudflare Workers cloud (ADR 0004): a Worker in TypeScript with a D1 database. It serves only data that is the same for every Viewer. Provider logins, stream URLs and Provider requests never come here (ADR 0001).

Deployed at `https://better-iptv-cloud.victor-trinh.workers.dev`; the app's `cloudUrl` (in `gradle.properties`) points there.

## API

Every endpoint but registration needs `Authorization: Bearer <device token>`, and answers `401` without a token a device was given.

- `POST /v1/devices` registers a device: `201 {"token": "…"}`. The app does this on first use and keeps the token in its database. The cloud keeps only the token's SHA-256.
- `POST /v1/metadata` takes `{"language": "fr", "titles": [{"kind": "movie", "tmdbId": 238}]}`, at most 50 Titles, and answers `{"titles": […], "notFound": […], "unavailable": […]}`. Each Title has its names in every language, its original language and title, alternative titles, descriptions in the requested language and English, genres in the requested language, popularity, release date, runtime, poster and backdrop paths (for `https://image.tmdb.org/t/p/<size><path>`), cast, IMDb ID and YouTube trailer IDs. See `src/tmdb.ts`.

TMDB's answers (including "no such Title") are cached in D1 per Title and language for a week (a day for "no such Title"), so a repeat request doesn't reach TMDB. When TMDB can't answer, the last cached answer is served, and Titles never fetched are listed as `unavailable`. The app also caches what it gets, for a week.

Registration is open: a token identifies a device, so it can be revoked, but it doesn't stop someone from registering one. That's acceptable while the cloud only serves public TMDB data at personal scale.

## Secrets

- `TMDB_TOKEN`: TMDB's API read access token (from themoviedb.org → Settings → API). It lives only here, never in the APK. Set it with `npx wrangler secret put TMDB_TOKEN`.

## Develop

```sh
npm install
npm test          # seam 2: the API in the Workers runtime (Miniflare), with TMDB faked
npm run typecheck
npm run dev       # a local Worker on :8787; put TMDB_TOKEN=… in .dev.vars
```

Point a debug build at a local Worker with `./gradlew assembleDebug -PcloudUrl=http://<this Mac's IP>:8787`.

## Deploy

CI (`.github/workflows/cloud.yml`) tests every change under `cloud/`, and on `main` applies D1 migrations and deploys. It needs two repository secrets:

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token from the "Edit Cloudflare Workers" template, with D1 edit added.
- `CLOUDFLARE_ACCOUNT_ID`: the account ID `npx wrangler whoami` shows.

To deploy by hand: `npm run deploy`. Schema changes go in a new file under `migrations/`.
