---
status: accepted
date: 2026-10-24
---

# Titles are identified by TMDB ID, not by Listing names

Every Listing is Matched to a Title by TMDB ID and becomes one of that Title's Versions. Matching by cleaned-up names was the obvious alternative, but it misses translated names (`FR - Le Monde fantastique d'Oz` and `EN - Oz the Great and Powerful` are the same film): on the surveyed Provider, names collapse 144,031 movie Listings to 98,525 Titles while TMDB IDs collapse them to the true 54,658. The Provider supplies the ID on 94% of Listings; the rest are looked up on TMDB by cleaned name and year, with the answer cached in the cloud.

## Consequences

- Provider IDs are checked, not trusted: a Listing whose name matches neither the other Listings of its Title nor TMDB's titles in any language is treated as a Mismatch and Matched again.
- Movie and show TMDB IDs are separate number spaces, so a Title is keyed by kind and ID.
- Shows are merged per Episode, so a Show offers every season any of its Listings has.
- TMDB is a hard dependency. Its API is free for non-commercial use only; a paid version of the app needs a commercial TMDB licence.
