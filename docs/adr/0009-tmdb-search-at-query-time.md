---
status: accepted
date: 2026-10-28
---

# Search asks the cloud for TMDB names and cast at query time instead of prefetching the catalog's metadata

Search runs on the device over the stored catalog, but TMDB names ("Der Pate") and cast only reached Titles whose metadata was already cached, which mostly means Titles the Viewer had seen. To make them reach every Title, the device now sends the query to the cloud, which runs TMDB's search for Movies, Shows and people, then hands back TMDB IDs. The device keeps the ones the Provider has and merges them with its own matches. We measured the alternatives against the surveyed catalog (53,865 Movie and 14,818 Show TMDB IDs, about 69k Titles), using a sample of 250 Titles sent through the deployed cloud:

| | Requests per Sync | Stored on the stick | TMDB fetches |
|---|---|---|---|
| Prefetch full metadata | ~1,380 per App language (50 Titles each, 0.3 s cached, 1.5–2.5 s not) | ~184 MB (2.7 KB per Title) | ~69k, again every week |
| Prefetch search fields only | ~140 | ~25–34 MB (370–490 B per Title) | ~69k, again every week |
| **Search at query time** | none | none | ≤ 4 per new query, cached |

Both prefetches would also make the device parse every Title's metadata to rebuild its in-memory search index after each Sync, which already takes about a second on a laptop without them.

## Bounds

- The device asks only once typing pauses (400 ms) and the query has at least 3 letters. Below that, and whenever the cloud can't answer, search shows the device's own matches.
- The cloud makes one TMDB multi search (page 1: 20 Movies, Shows and people), then fetches full credits (cast and directing) for at most the 3 most popular people. It caches each normalized query for a day and each person's credits for a week, so a repeated query costs no TMDB call.

## Consequences

- Listing names still match instantly, offline and with typo tolerance. TMDB names and people arrive about 0.3–1 s later and are only as tolerant of typos as TMDB's search.
- The query text leaves the device, to our cloud and to TMDB. The catalog doesn't: the device decides which IDs the Provider has.
- A person is found through all their credits, not only Titles whose top-12 cast lists them.
