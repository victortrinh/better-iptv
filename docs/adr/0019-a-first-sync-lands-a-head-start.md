---
status: accepted
date: 2026-11-15
---

# A first Sync lands a Head start of fresh Categories before it reads the rest

Extends [ADR 0011](0011-later-syncs-land-between-screens.md), which landed a first Sync's Channels within seconds and its Titles all at once at the end.

That end is about three minutes away on the Fire TV Stick (186–198 s measured), and until then Home shows placeholder posters and the Movies and Shows tabs a checklist. The app is called Zugig, quick, and its first minutes are the slowest it ever is.

The wait is the device's own work, not the Provider's. Measured on 2026-11-15 against the real catalog (45.8k Channel variants, 145.5k movie and 36.8k show Listings):

| Provider download | Decoded | Time from the Mac |
|---|---|---|
| Channels | 17 MB | 1.6 s |
| Movies | 59 MB | 5.8–6.5 s |
| Shows | 49 MB | 5.8–7.6 s |

| Step (first Sync on the Mac's JVM, 5.3 s in all) | Share |
|---|---|
| Channels | 18% |
| Movies | 23% |
| Shows | 25% |
| Saving | 34% |

No Step dominates: a show Listing costs 4.4 times a movie Listing (its plot, cast and backdrops), and Saving is spread over five statements. Nothing cheap makes the whole Sync quick. The Provider does serve one Category alone when asked: `EN - NEW RELEASE`, 6,670 movies, in 1.3 s.

So a first Sync, right after its Channels, reads a **Head start**: per Content language, at most two movie and two show Categories that the Prefix table says hold new releases (words the Base table ships, plus Hand lines for names such as `FR - LE DRENIER AJOUTEE`). A language with no such Category of a kind is read from its first Category on, in the Provider's order, until 40 Listings that show are read or three Categories are. The Category list carries no counts, and the first Category in a language can be a quality variant holding one Listing: the surveyed Provider's English shows open on `ENGLISH SERIES ⁴ᴷ ³⁸⁴⁰ᴾ` (1 Listing) and `ENGLISH SERIES ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ` (9) before `ENGLISH SERIES` (3,316), so the first alone made a Shows tab of a single card. It lands them as real, playable Titles, and Home's and the tabs' "New" rows and the featured Title fill from them. The full catalog is then read as before and replaces the Head start whole, at once, the focused card and its row staying where they are. The checklist shows only until the Channels land; after that the Catalog pill says the rest is still being added, and Search says so under an empty result.

## Considered

- **Landing the full list in batches as it is read.** A Title's Listings are spread through the list, so cards would merge and rows reshuffle for minutes, and a Mismatch can't be told until all of a number's Listings are in.
- **Landing Movies before Shows.** Movies are read at about 40% of the Sync and would need a Saving pass of their own: a late first result for a second landing.
- **Only making the Sync faster.** Worth doing, but the time is spread over four Steps, and where the stick loses its 36× over the Mac isn't known yet.

## Consequences

- A first Sync makes a handful of extra Provider requests, one at a time, before the three big ones: per Content language and kind, two at most for fresh Categories, three at most without one.
- Until the full catalog lands, a Head start Title has only the Versions its Head start Listings give it, Search and the genre rows know only the Head start, and Trending isn't shown.
- A Head start that can't be read (a Provider that refuses a per-Category request) is skipped without an error; the first Sync then behaves as ADR 0011 describes.
- An app closed between the Head start and the full catalog reopens on the Head start, and the Sync starts again from the full read.
- The full catalog never lands under the player: one that is ready while a Head start Title (or a Channel) plays waits as a Pending catalog and lands as the player closes, on whatever screen the Viewer comes back to. An app closed meanwhile reopens on the full catalog.
- What the Viewer did during the Head start is kept: a Title keeps its identity, so where they stopped stays with it, and a Favourite is a Channel's, which the Head start never touches.
- A later Sync has no Head start: it still lands between screens (ADR 0011). A catalog dropped by a schema change is a first Sync again.
- Title facts and Background reading wait for the full catalog, as before.
