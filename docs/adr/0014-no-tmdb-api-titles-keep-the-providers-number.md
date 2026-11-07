---
status: accepted
date: 2026-11-07
---

# TMDB's API is no longer called; Titles keep the Provider's number as their key

Amends [ADR 0002](0002-titles-identified-by-tmdb-id.md) and supersedes [ADR 0009](0009-tmdb-search-at-query-time.md).

Zugig will be sold for a one-time price (M7). TMDB's API is free for non-commercial use only, and its commercial licence is 149 USD a month, which a one-time price cannot carry. So before the first sale the app and the cloud stop calling TMDB: no API key, no metadata, search, trending, Discover, season or upcoming-Episode calls, and the cached answers are deleted from the cloud's D1 and from every device on the first launch after the change, as TMDB's terms ask when use ends.

What stays is the number. The Provider files each Listing under a `tmdb` number, the same for every Listing of a work in every language. Reading it from the Provider's catalog is not using TMDB's API, and nothing else groups the catalog: cleaned names collapse the surveyed 144,031 movie Listings to 98,525 Titles, the number to 54,658, and of the Titles with both English and French Listings, 44% have a French name sharing no word with the English one (2026-10-24 survey, re-read 2026-11-07). OpenSubtitles and Subdl are searched by the same number. In CONTEXT.md it is the Title number: the Provider's cross-reference, which happens to be TMDB's numbering.

What the Provider gives instead was measured on 2026-11-07 (Surveys A and B in docs/roadmap.md, M6): a poster per Listing and a backdrop per Title in its own catalog and details, plot, cast, genre, runtime, release date, a trailer key on about half the Titles, a still on 94% of Episodes. The posters and backdrops are mostly TMDB-hosted files the Provider links to; the app loads the URLs the Provider gives, as every Xtream player does, and the question is on the lawyer's list for M7.

Wikidata (CC0) replaces TMDB for two things only, the ones no Provider field holds: a Title's original language (which makes an Untagged Version Likely original) and its Wikipedia article, through which Wikipedia's daily most-viewed list gives a trending row. Both are one lookup per Title number, cached for good.

## Consequences

- The 6% of Listings without a number are no longer looked up by name. TMDB's search found one in seven of them; Wikidata's finds fewer; they are catalog filler. They stay Unmatched Titles, one card each.
- A Mismatch (a wrong number) is told by its year, not by a name check against a database: a Listing dated more than a year from its siblings' becomes an Unmatched Title. The surveyed catalog has 181 such Titles among 19,148 with two or more dated Listings.
- Title logos and TMDB's curated textless art are gone; the look is built on the Provider's posters and backdrops (M6 in the roadmap says how). Charts are gone: the Netflix Top 10 was already leaving (M7), trending comes from Wikipedia's pageviews, and genre rows come from the Provider's genres and Categories.
- Search runs on the device alone, over the Provider's names in every language it carries and the cast the Provider's details name; there is no cloud search. Upcoming Episodes are not shown.
- The cloud keeps no TMDB data and needs no TMDB key. Its Title endpoints shrink to the Wikidata lookup, subtitles, shared Header reads, unknown Prefixes and the sports schedule.
- If a commercial TMDB licence ever becomes affordable, this ADR is the one to revisit: the key is already TMDB's numbering, so nothing would have to be re-matched.
