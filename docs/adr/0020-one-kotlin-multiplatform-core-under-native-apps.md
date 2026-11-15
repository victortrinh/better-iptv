---
status: accepted
date: 2026-11-15
---

# One Kotlin Multiplatform Core under a native app per platform family

Zugig is going to phones, Samsung TVs, Apple devices and desktops, and ADR 0001 holds on all of them: every device Syncs and Matches the catalog itself. So the Core (14,000 lines of plain Kotlin with 15,800 lines of tests, already free of Android) becomes Kotlin Multiplatform and compiles to Android, to native code for Apple, Windows and Linux, and to JavaScript for Samsung. Each platform family draws it with its own interface: Compose on Android, Windows and Linux, SwiftUI on iPhone, iPad and Mac, HTML on Samsung. The Core hands every interface finished screen content (its Rows, what Play does, a Game's Feeds in order), so the apps can't disagree and a new platform is mostly drawing. This amends ADR 0003, which expected another platform to share only the cloud and the domain model.

## Considered

- A Rust Core with bindings: the fastest raw code, but a full rewrite, and the slow parts (SQLite, the network) are native code either way. On Android every call would cross a bridge.
- One web interface everywhere: the least code, but it replaces the Compose and Media3 app ADR 0003 chose for the Fire TV Stick's speed.
- The cloud processing the catalog for thin clients: rejected by ADR 0001, and nothing forces it, since the Provider's API answers over HTTPS to any origin.
- A Core written again per platform: copies of Match and the Prefix table that drift apart.

## Consequences

- The move is made in steps while the Android app keeps shipping: the Core goes multiplatform with Android as its only target, its JVM-only libraries (HTTP, JSON, dates) are swapped one at a time, then the Apple target is added, then Samsung's. The stick's Sync time is recorded before and after the JSON swap.
- Storage is one SQLite design everywhere. On Samsung it runs as WebAssembly, which is unmeasured: the first Samsung ticket times a first Sync of the real catalog on a real set, and a second storage layer is designed only if that fails. Samsung sets from 2021 are supported (Chromium 76).
- Search moves from an in-memory index, rebuilt on every launch, to SQLite's full-text index built during a Sync, on Android too, once the stick shows typing is as fast and results rank the same.
- The Core names what it needs from a platform (keep the Provider login secret, keep a setting, run a Sync in the background) and each platform supplies it. The Player engine stays the platform's: Media3 on Android, mpv on Apple and desktop, Samsung's own player on its TVs.
- There is no web app to watch in. A desktop browser can't play most of what a Provider serves; the web holds the sign-in and payment pages only.
- Roku, Vega OS and Xbox are out: the Core can't run on the first two. LG and Apple TV wait for a set to test on.
