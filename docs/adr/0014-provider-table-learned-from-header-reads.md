---
status: accepted
date: 2026-11-07
---

# A Provider's Prefix meanings are learned in the cloud from Header reads, with hand lines only where reads disagree

Zugig was built against one surveyed Provider, whose 150 Prefixes and 30 Category hints were written by hand into `prefix-table.txt`. Opening the app to any Provider means meeting panels nobody has surveyed, whose Prefixes may mean anything (`CD` is German on the surveyed Provider, a typo of `DE`). The table now splits: a Base table shipped with the app holds the conventions most Xtream panels share, and a Provider table, served by the cloud under the Provider's host hash, holds what is that Provider's own. The Provider table is mostly learned: each file-read report a device already sends (ADR 0005) gains the Listing's Prefix and matched Category hints, the cloud tallies per Provider and Prefix, and an attribute is learned once about 10 reads agree at 90%. A language is learned only from single-audio files; a Prefix whose files mostly carry several audio languages learns "multi-audio", which the device treats as Untagged, since an `NF` file with 7 audio Tracks would otherwise teach English. Learned attributes show as Likely, with no third certainty level. Hand lines, one file per Provider hash in the repo and loaded into D1, cover what reads can't teach (Clutter, Tier tags, Sources, the Event time zone) and Prefixes whose reads split under the bar; a hand line wins over a learned one, and a learned one over Base.

## Considered

- **A hand table per Provider, written by the developer** from each new Provider's unknown-Prefix report: what M0 did for one Provider, and about a day of work per panel that only the developer can do, for panels the developer has no login to.
- **Sending Listing names to the cloud** so it could read Prefixes itself: the cloud would learn each household's catalog, which ADR 0001 keeps on the device; a code and a hint word reveal nothing a Title name would.
- **Learning on the device alone**, from its own reads: one household reads a few dozen files a week, so a Prefix would take months to settle, and every household would relearn the same panel.

## Consequences

- A new Provider is honest on day one (unknown Prefixes say "Language not named") and useful in week two, as long as some of its Viewers open Titles; reads of unknown-Prefix Versions go first for that reason.
- Measurement beats convention: a learned line that contradicts Base applies, and the weekly unknown-Prefixes issue lists the contradiction and every split for a hand line.
- Every device reports its reads, whether or not it is Unlocked (ADR 0013): the learned table is what a free household gives back, and it stays free.
- The surveyed Provider's typos and own codes move out of the shipped table into its hand file, so a second Provider never inherits `CD` meaning German.
