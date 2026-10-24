---
status: accepted
date: 2026-10-24
---

# The Provider catalog is synced and processed on the device; the cloud never holds Provider credentials

The device downloads the Provider's catalog itself, Matches it and stores it in a local database; the cloud only serves data that is the same for every Viewer (Charts, Game schedules, Title metadata, subtitles, shared Header read results). Having the cloud process the catalog would give a faster first launch, but it would make us custodian of every Viewer's IPTV login and stream URLs once the app is opened to others, and some Providers flag or block logins from datacenter IPs. The survey showed the device can cope: the full catalog (226k Listings) is 22 MB compressed and downloaded in about 9 s, and 94% of Listings already carry a TMDB ID, so Matching needs almost no remote lookups.

## Consequences

- The first Sync on a new device is the only wait; later Syncs run in the background while the app shows the previous copy.
- Anything that needs the Provider (Sync, Header reads, playback, the TV guide) runs on the device, from the Viewer's home IP.
- Hiding unwanted languages is a browsing filter, not a speed measure: the whole catalog is downloaded regardless.
