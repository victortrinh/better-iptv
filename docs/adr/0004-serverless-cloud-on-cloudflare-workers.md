---
status: accepted
date: 2026-10-24
---

# The cloud runs serverless on Cloudflare Workers, in TypeScript

The Viewer doesn't want anything running at home, so the cloud is a set of Cloudflare Workers with a D1 database and scheduled jobs, written in TypeScript. It only runs when called or on a schedule and costs nothing at personal scale, unlike a VPS, and unlike a device-only design it gives a shared cache and a place for accounts later. It also keeps API keys (TMDB, OpenSubtitles, Trakt) out of the APK.
