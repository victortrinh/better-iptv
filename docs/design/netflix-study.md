# Netflix on the Fire TV Stick: what to take

Studied 2026-10-30 for [#129](https://github.com/victortrinh/better-iptv/issues/129) on the household's Fire TV Stick 4K Max (Netflix `com.netflix.ninja`, the 2025 TV redesign). Netflix blocks screen capture (`FLAG_SECURE`) and draws everything in one custom view with no accessibility tree, so the remote was driven over adb and the TV was photographed from the couch. The photos aren't in the repo because they show the living room.

Sizes are estimated from those photos and given in dp/sp on our 960 x 540 canvas (see [DESIGN.md](../../DESIGN.md)). Treat them as ±15%.

## The short list

Worth adopting, most valuable first:

1. **Search completions under the keyboard.** After "br", a list of one-press completions mixing titles, people and genres (Bridget, Brooklyn, Brad Pitt, Brazilian Movies). On a D-pad this saves more presses than anything else on the screen. Before anything is typed, the same slot lists genre shortcuts.
2. **The Play button says what it will play.** "Resume S1: Ep. 6" with a small progress bar inside the button, or "Play Limited Series: Episode 7". The details page describes that episode, not the show. Our Show details have no Play button.
3. **Details actions as a vertical list.** One wide primary capsule, then unboxed icon + label rows ("Play From Beginning", "More Episodes", "Audio & Subtitles"). Only the focused row gets a capsule. This also fixes our "Continue from / Restart / Remove" row overflowing the 470 dp column.
4. **Back from the rows jumps to the tabs.** The tab bar stays pinned. Back moves focus to the current tab without scrolling, and Down returns to the same card.
5. **Text is bigger.** Descriptions are about 17–18 sp (ours 15), and posters about 170 dp wide (ours 104).
6. **The hero leaves on the first Down.** The whole hero scrolls away, and past it the focused card does the hero's job: a wider image with the metadata line and description under it. This closes our "hero should recede" gap.
7. **Audio and subtitles show their current values.** Three cards along the bottom of the player: Subtitles, Audio Language, Subtitles Appearance. The focused card opens upward into its list; the others show only their current choice. The audio track is labelled "Original: English".
8. **Episodes open on the next one to watch.** The previous episode peeks above. Unreleased episodes say "Available October 31" instead of a description.

Not for us:

- **Muted video previews in cards and on details.** With one Provider stream, a preview would compete with Play, and the stick can't afford it. A still does the same job.
- **Red as the accent, and red status flags** ("New Episode", "Live Now", "Top 10"). Verre keeps colour in the artwork.
- **One flat search grid mixing Movies and Shows.** Our "Movies · n / Shows · n / Channels · n" rows say more, and Channels need their own card shape.
- **Fixed-pivot horizontal scrolling.** This one needs a decision, discussed under [Movement](#movement).

## Screen by screen

### Profile picker
A vertical column of avatars on the left over black, with promotional art for a title filling the rest. The focused avatar grows by about 1.3x, gets a white rounded rim, and shows its name in bold beside it. The edit pencil sits one press to the left of the focused avatar. We have no profiles, so nothing to take beyond "the focused thing grows and names itself".

### Home
- **Tab bar:** Netflix's avatar and caret sit far left, a centred row holds search (icon, first), Home, Shows, Movies, Games and My Netflix, and the red N sits far right. The selected tab is a glass pill and the focused tab is white with dark text, the same grammar as our `TopTabs`. The bar is **pinned**: it never scrolls away. Netflix has no Settings tab.
- **Hero:** a large rounded card inset to the margins (not full bleed), about 75% of the height. The title is its **logo art**, not typed text. Under it is one bold metadata line ("Movie · Kids · 2024 · 1h 34m · PG") with **no description**, then Play (white, focused) and More Info (glass). First focus lands on Play. A row of genre tiles peeks in at the bottom edge.
- **First Down:** the hero scrolls away entirely and the genre tiles move up under the tabs. A tile's focus is a white rim plus a slight scale, with no fill.
- **Rows:** every row is posters, about 170 dp wide, with about five and a half across, the last cut off by the right edge. No text under the cards: the logo is part of the poster. Continue Watching adds a red progress bar under each poster. The next row's heading and poster tops always peek in at the bottom.
- **A focused poster widens into a 16:9 card** about 480 dp wide, at the same height, playing a muted preview. Its neighbours slide right. Under it, left-aligned:
  - a Title in progress: "S1 E6 · We Have The Power" and "7m left" (bold);
  - a Title not started: the metadata line (about 13 sp, semibold) and a three-line description (about 17–18 sp, regular), about half the screen wide.
- **Back** steps out one level at a time (episodes → details → Home) and returns to the card that opened it, at the same scroll position. On Home, Back jumps focus to the current tab without scrolling the content.

### Details
- Full screen, no tab bar. A text column takes the left ~45% on black, and the backdrop or trailer fills the right ~65% under a strong left-to-right scrim.
- Top to bottom: the logo; a metadata line with **outlined capability badges** for this device ([4K] [5.1] [AD] [CC]); a filled rating badge (TV-MA) with the content advisory; the **next episode's** name in bold and its description (three lines); a status line ("New Episode Coming Saturday"); unlabelled icons for remind me and the three ratings; then the action list.
- **Action list:** rows of equal width (about 280 dp). The primary row is a capsule naming exactly what it does ("Resume S1: Ep. 6" with its progress inside). The others are icon + label with no container. Only the focused row is a white capsule, so Play loses its capsule once focus leaves it. Audio & Subtitles can be set before playing.
- A collection row ("Breaking Bad Collection") peeks in at the bottom.
- Back from the player returns here with the **frame where you stopped** frozen as the backdrop.

### Episodes & More
Two panes. On the left: the logo, a short metadata line, icons, and a section list ("Limited Series · 12 episodes", "Trailers & More · 2 videos", with seasons listed here). The section on show keeps a glass pill while focus is on the right, so selected and focused look different. On the right: a vertical list where each episode is a 16:9 still (about 190 dp) with "S1: E7" printed on it and a progress bar, beside its name (bold, about 16 sp), a three-line description and its runtime. Focus opens on the next episode to watch and is a white rim on the still only.

### Search
- **Keyboard:** about 200 dp wide on the left. Space and Delete are two wide keys on top, then a six-column alphabetical grid (a–z, 1–0) of square keys about 30 dp with 2–3 dp gaps. First focus is on "a". A key's focus is a **white rim**, not a fill.
- **Under the keyboard:** genre shortcuts while empty, then **completions** once you type.
- **Results:** a four-column poster grid, about 125 dp posters, scrolling vertically. Empty, it's headed "Your Search Recommendations". Typing replaces that heading with the query as plain text ("🔍 br", about 18 sp, no field box). Results update after every letter, ranked personally (Breaking Bad and El Camino near the top for this household).

### Player
- **Start:** no controls, just the picture. A small rating card shows top left for a few seconds: a thin red bar, "Rated TV-MA" and the advisory. Subtitles are large white text with a soft shadow and no box.
- **Paused (OK):** a dark gradient rises from the bottom, and everything sits in **one left-aligned column** at the margin: the logo (no episode name or number), a rating prompt near the end ("Enjoying this?" with three capsules), three bare icons (Start Over, Next Episode, Audio & Subtitles), then a margin-to-margin scrubber (red played, grey rest, white thumb) with elapsed time under its left end and **time remaining** under its right. There's no Play/Pause button and no ±10 s: OK toggles pause, and Left/Right on the scrubber seek.
- **Focus on the icons:** the focused icon becomes a white circle with its name **above** it in small bold type. The scrubber collapses to a thin line with no thumb or times until focus returns to it.
- **Audio & Subtitles:** described in the short list (item 7). The current choice carries a ✓ and a glass pill, and the focused row is a white fill.

### Settings
None in the app. The avatar switches profiles, and settings live on the web. Our Settings tab has no Netflix counterpart.

## Movement

Netflix keeps focus in one place on screen and moves the content under it:
- **Vertically,** the focused row always jumps to the top, just under the pinned tab bar, with one row peeking below.
- **Horizontally,** the focused card stays at the left margin and the row slides. The previous card is left as a sliver at the left edge.

Ours is the opposite (EdgeScroll, The Still Screen Rule): lists hold still while focus moves inside a band, and scroll only near an edge.

What a fixed spot buys is a predictable place for the focused card's info, since it's always in the same spot under the card. What it costs is that every press moves the whole screen, which on the stick means a full row re-layout per press. That is what #33 and #63 worked to keep cheap.

Suggestion: keep EdgeScroll horizontally, and try Netflix's vertical behaviour on Home only: the focused row snaps under the tabs, so the info slot under the focused card always sits at the same height. This changes a Named Rule, so it needs your decision before a ticket.

## Type and size comparison

| | Netflix (est.) | Zugig today |
|---|---|---|
| Row heading | ~17 sp bold | 20 sp (`headline`) |
| Description | ~17–18 sp regular | 15 sp (`body`) |
| Metadata line | ~13–14 sp semibold | 15 sp muted (`body`) |
| Episode name in a list | ~16 sp bold | 15 sp (`label`) |
| Poster width | ~170 dp | 104 dp |
| Focused card | 16:9, ~480 dp | poster, 1.12x |
| Search key | ~30 dp, 2–3 dp gaps | 34 dp, 6 dp gaps |
| Gap between buttons | ~12 dp | 12 dp |

Netflix spends size on descriptions and artwork and keeps headings modest. We do the reverse: bigger headings, smaller body text and posters.

## Where we already match or do better

- The focus grammar is the same: white fill on controls and tabs, a white rim on artwork, glass for selected.
- Back returns to the card that opened a page, in the same row.
- Our Versions table, certainty badges and per-language grouping have no Netflix equivalent, and are why the app exists.
- TMDB serves title logos (`/images` → `logos`). Using them in the hero and on details, as Netflix does, is cheap and needs no new source.
