---
status: accepted
date: 2026-11-02
---

# A later Sync's catalog lands between screens, never under focus; a first Sync lands its Channels first

A Sync used to swap the whole catalog in the moment it was ready, and every screen read its rows again at once, under the Viewer's focus: rows reordered, a focused card moved, a Favourites row slid down as Title rows came in. On a TV, where focus is the only pointer, that is the most jarring thing a screen can do. A first Sync also fetched the Channels last, so the Viewer waited the full two minutes of a 140k-Listing movie list before Live had anything to show, under a single climbing number that counted movie Listings and froze while the Channels downloaded.

Now a Sync has two Stages, Channels then Titles. A first Sync lands each as it is ready: Live and Home open on the Channels within seconds, under a checklist that names the Step the Sync is at, and the Title rows fill in place when the Titles land. A later Sync computes both Stages behind the catalog on screen and holds the result as a Pending catalog (`SyncStatus.Ready`), which the app lands when it comes to the front or a top-level screen is entered, never while the player is open. Matching after a Sync runs under its own status, so "Synced" is never written beside "still regrouping".

## Consequences

- Rows change between screens, not under focus. A Viewer who presses Sync now in Settings sees the new catalog on the next tab.
- Two catalogs can sit on the device while one is held: the Titles tables roughly twice over, bounded, and the write-ahead log is checkpointed after each Stage.
- A Sync started over a held catalog discards it; the stale-check (6 h) runs on launch and on each resume, never on a timer, and not while a catalog waits.
- Tests keep the old contract by default (`sync()` lands at once); the app passes `hold = true`.
