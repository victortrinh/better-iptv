---
name: Zugig
description: Verre, tvOS-style glass on a Fire TV. The artwork is the interface; everything else is black, white and translucent white.
colors:
  ground: "#07080A"
  glass: "rgba(255, 255, 255, 0.12)"
  glass-high: "rgba(255, 255, 255, 0.20)"
  ink: "#FFFFFF"
  ink-muted: "#A9A9AF"
  focus: "#FFFFFF"
  on-focus: "#000000"
  confirmed: "#E8E8ED"
  on-confirmed: "#000000"
  likely: "#C7C7CC"
  caution: "#FFB35C"
  error: "#FF6B60"
typography:
  display:
    fontFamily: "Inter"
    fontSize: "42px"
    fontWeight: 700
    lineHeight: "44px"
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "Inter"
    fontSize: "15px"
    fontWeight: 700
    lineHeight: "20px"
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Inter"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "19px"
  body:
    fontFamily: "Inter"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
  detail:
    fontFamily: "Inter"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18px"
  label:
    fontFamily: "Inter"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: "18px"
  rail-meta:
    fontFamily: "Inter"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: "18px"
  rail:
    fontFamily: "Inter"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "22px"
  rail-strong:
    fontFamily: "Inter"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: "22px"
  caption:
    fontFamily: "Inter"
    fontSize: "11px"
    fontWeight: 600
    lineHeight: "15px"
    letterSpacing: "0.01em"
rounded:
  badge: "4px"
  card: "12px"
  control: "22px"
spacing:
  screen-x: "48px"
  screen-y: "27px"
  xs: "4px"
  s: "8px"
  m: "12px"
  l: "16px"
  xl: "24px"
  xxl: "32px"
  card-gap: "20px"
components:
  button-glass:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "0 18px"
    height: "40px"
  button-glass-focus:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.on-focus}"
  button-glass-disabled:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink-muted}"
  tab-bar:
    backgroundColor: "{colors.glass}"
    rounded: "{rounded.control}"
    padding: "4px"
  tab:
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6px 14px"
  tab-selected:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink}"
  tab-focus:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.on-focus}"
  title-card-poster:
    rounded: "{rounded.card}"
    width: "136px"
  title-card-wide:
    rounded: "{rounded.card}"
    width: "196px"
  badge-confirmed:
    backgroundColor: "{colors.confirmed}"
    textColor: "{colors.on-confirmed}"
    typography: "{typography.caption}"
    rounded: "{rounded.badge}"
    padding: "2px 6px"
  badge-likely:
    textColor: "{colors.likely}"
    typography: "{typography.caption}"
    rounded: "{rounded.badge}"
    padding: "2px 6px"
  setting-row:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "12px 16px"
  setting-row-focus:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.on-focus}"
  version-row:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  version-row-focus:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink-muted}"
  text-field:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.card}"
    padding: "0 16px"
    height: "48px"
  text-field-focus:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink}"
  choice:
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "36px"
  choice-selected:
    backgroundColor: "{colors.glass-high}"
    textColor: "{colors.ink}"
  choice-focus:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.on-focus}"
  key:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    size: "34px"
  key-focus:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.on-focus}"
---

# Design System: Zugig

This is direction #3, "Verre", written down for the agents picking up screen tickets. Ground truth is the shipped code: tokens in `app/src/main/kotlin/dev/betteriptv/app/ui/theme/Tokens.kt` (`Colors`, `Type`, `Corners`, `Spacing`, `Cards`, `FocusScale`), the theme and scrolling in `NuageTheme.kt`, components in `ui/components/`. Screens read tokens, never raw values.

**Units.** The app draws on a 960 x 540 dp canvas (1920 x 1080 at density 320). Every `px` in the frontmatter is a dp (or sp, for type) on that canvas, read from about 3 m.

## Overview

**Creative North Star: "The Artwork Is the Interface"**

Verre is the Apple TV app (tvOS Liquid Glass) on a Fire TV. A Title's artwork fills the screen, edge to edge, and the interface floats on it as white type and translucent white glass. The interface brings no colour of its own. The ground is near-black, controls are glass capsules, posters have softly rounded corners, and the one solid white thing on screen is what the remote will act on.

The world refuses the category default: no grey streaming grid with a coloured accent, no Netflix black-and-red. Density is low on the browsing screens (one featured Title big, rows below) and tabular on details, where every Version of a Title lines up by language so the household can compare copies at a glance. Honesty about each Version is visual: a Confirmed attribute is a solid badge, a Likely one is an outlined badge, everywhere.

The Fire TV Stick is weak, so the glass is a flat translucent tint, never a live blur, and every effect is cheap: a scale, a shadow, a gradient, a border.

**Key Characteristics:**
- Near-black ground; white and translucent white glass (12% and 20%) for everything that isn't artwork.
- Full-bleed backdrop behind the hero and details, held readable by scrims.
- Inter throughout; bold, tightly tracked display.
- Capsule controls, 12 dp poster corners, 4 dp badges.
- White fill means focus. Focused artwork lifts 1.12x with a deep shadow, a light sheen and a bright rim.
- A card's name and badges appear only under the focused card.
- Lists scroll only when focus nears an edge (EdgeScroll).

## Colors

Achromatic by design: black ground, white ink, white glass. Colour on screen comes from artwork, plus amber for cautions and red for failures.

### Primary
- **Focus White** (`focus`): the fill of whatever has focus (buttons, tabs, keys, choices, settings rows), with `on-focus` black text on it. It is the app's only accent.

### Neutral
- **Near-Black Ground** (`ground`): behind everything, and the colour every scrim fades artwork into.
- **Glass** (`glass`, white at 12%): the resting surface of rows, fields, keys and the tab bar. Translucent, so artwork shows through.
- **Strong Glass** (`glass-high`, white at 20%): unfocused buttons, the selected tab, the selected choice, and focused Version rows and text fields.
- **Ink** (`ink`): primary text, the text cursor, card progress bars.
- **Muted Ink** (`ink-muted`, about 8.6:1 on the ground): secondary text such as metadata lines, column headings, setting details and current values. It also marks a disabled button's label.

### Certainty
- **Confirmed Fill** (`confirmed`) with **On-Confirmed** black text: a Confirmed badge. It is slightly off-white so it never reads as a focused control.
- **Likely Outline** (`likely`): the 1.5 dp outline and text of a Likely badge.

### Status
- **Caution Amber** (`caution`): cautions that aren't failures, such as a CAM copy or an Audio-description Version. On the details screen it is the colour of the note under a Version's badges.
- **Failure Red** (`error`): a failed login or Sync message, as text only.

### Named Rules
**The Artwork Carries the Colour Rule.** The interface adds no hue. The only non-neutral tokens are Caution Amber and Failure Red, and they appear as small text, never as fills, tabs or highlights.

**The White Means Focus Rule.** No control or row is ever filled solid white unless it has focus. Three small indicators are exempt: the white progress bar on a card and on an Episode row, and the off-white `confirmed` badge.

## Typography

**Display Font:** Inter 700
**Body Font:** Inter 400 and 600
**Label/Mono Font:** Inter 600 (no second family)

**Character:** One family, three weights (400, 600, 700, bundled as `inter_*.ttf`). The display size is bold and tracked tight so a Title's name reads like a poster credit. Everything else is plain and even, built for reading from a couch. The sizes were set on the Fire TV Stick itself, close to tvOS and Material for TV on the same canvas: the earlier scale, estimated from photos of Netflix, read too big on the TV (#186).

### Hierarchy
- **Display** (`Type.display`): a Title's name in the home hero and at the top of its details page. Two lines at most. A Title with a TMDB logo (in the App language, else English) shows the logo there instead (`TitleName`): fitted in a 320 × 88 dp box, the room two Display lines take, and sitting on its bottom edge, with the name as its content description. The box stays empty while the logo loads, and the name shows in Display type when there is no logo or it can't be loaded.
- **Headline** (`Type.headline`, 15 sp): row headings ("Continue watching"), screen and section headings ("Versions", "Playback"), the player's Title name. Kept modest so the posters under a heading lead, as on Netflix (#147). The Wordmark uses it at 18 sp.
- **Title** (`Type.title`): a Title's name under a focused card, the language group heading over Versions ("English · 3"), the search query.
- **Body** (`Type.body`, 14 sp): descriptions, metadata lines, people, messages, field text: what is read from the couch. Descriptions are capped at 2 lines in the hero and 3 on details.
- **Rail** (`Type.railMeta` 13 sp, `Type.rail` 16 sp, `Type.railStrong` 16 sp bold): the info under Home's focused rail, set as Netflix sets it (#182): a step above body, so the description reads like the artwork's caption, with the metadata line smaller and muted and the time left in Continue watching strongest.
- **Detail** (`Type.detail`, 13 sp): body beside label type in a row or table: Version cells and bitrate, setting values. It stays at 13 sp, the size of label type, so the French strings still fit the Version columns.
- **Label** (`Type.label`): buttons, tabs, choices, keys, setting labels, the Source cell of a Version row.
- **Caption** (`Type.caption`): badges, column headings, field labels, explanations under a setting, the Sync status line.

### Named Rules
**The Readable From the Couch Rule.** All text keeps at least 4.5:1 contrast against what is actually behind it. That includes glass over artwork, so for example inactive tab labels use white at 85%, not `ink-muted`. Nothing goes smaller than caption (11 sp).

**The French Is Longer Rule.** French strings run about 20% longer. Fixed widths (Version columns, the settings label column, buttons in a row) must hold the French string, and single-line text gets `maxLines` with an ellipsis instead of overflowing.

## Layout

**Overscan-safe margins.** Every screen keeps its content inside `screen-x` (48 dp) left and right and `screen-y` (27 dp) top and bottom. Scrolling lists apply the margin as `contentPadding` rather than outer padding, so cards scroll under the edge and are never clipped while focused. Login doubles the horizontal margin (96 dp) to centre a two-column form.

**The shell.** `TopTabs` runs across the top inside the margins: the Wordmark on the left and the tab capsule centred. Tabs are Home, Movies, Shows, Live, then Search and Settings as icons; Live arrives with the sports hub. The tabs stay on screen on Home, Movies, Shows and Search: the cards scroll beneath them and pass under their bottom edge. Picking a tab keeps focus on it, so Left and Right go on along the tabs and Down goes into the screen; Home's first card, Search's keyboard and Settings' first row take focus only when the screen is reached otherwise, as on launch. Up into the tabs lands on the selected one, not the nearest (#187). Back from any card focuses the selected tab and leaves the scroll position alone; Down from the tab returns to the card that had focus. Back on a tab does what it always did: Search goes to Movies, the other tabs leave the app.

**Home.** The featured Title's backdrop fills the screen. The hero text column (440 dp wide) starts 96 dp below the tabs, at the bottom-left third. In order: display name, a metadata line (year · runtime · genres in muted body) followed by its badges, two lines of description, then Play and Details buttons 12 dp apart. The first Down from the hero scrolls it fully off screen, under the tabs, so the first row's heading comes up to the top; Up to the hero brings it back whole. Past it, the info under the focused rail (`RailInfo`, below) does the hero's job, as on Netflix (#182). Artwork stays still: no video previews. The rows start 16 dp under the tabs: each a headline heading, 12 dp, then a `LazyRow` with `screen-x` horizontal and 10 dp vertical content padding, then, for the focused row only, its info 12 dp under it, then 14 dp before the next heading. The rows move like Netflix's, so focus stays in one place (#167): the focused card sits in the first slot at the left margin, and Right and Left scroll its row under it (`Keyline`). A rail with more cards than the screen shows at once (more than 7 posters, 6 stills or Channels, counting the one peeking in the left margin; `RailLoop`) loops: Right on its last card goes on to its first and Left on its first back to its last, with no end either way, and the card before the focused one peeks in the margin. A shorter rail stops at its ends, where Left and Right go nowhere (not up to the tabs), but its last cards also scroll to the left margin, leaving room after them, so the focused card always sits above the info. A Top row (`RankedCard`, below) never loops, whatever its length: a ranked list has a start and an end, so Left on its first card and Right on its last go nowhere, and its last card too scrolls to the margin with room after it. Its keyline puts the poster, not the numeral, at the focused place, so the numeral sits in the left margin. Down and Up bring the next row's heading to the top of the rows, right under the tabs, and the rows above pass out of sight; once it is there, the info moves under it and the rows below make way, so they don't shift while sliding. The row after the focused one peeks at the bottom. Each row keeps its own scroll, also while a Title's details are open, and Down into a row returns to the card focused there last. Posters (2:3, 136 dp) fill most rows. Continue watching uses 16:9 stills (196 dp) with a progress bar, so the Viewer sees where they stopped: Movies and Shows, last played first, a Show at the Episode to watch next. Then come the Charts and the browse rows, in order: Top movies and Top shows in the Region (`RankedCard`s), New on your service, and Trending this week, a plain poster rail that loops when long. Favourites follow, as `ChannelCard`s in the order they were added, and the row is left out while there are none; the genre rows come last. Removing a Favourite moves focus to its neighbour, or to the row that takes its place. Back from a Title returns focus to the card that opened it, in the same row even when rows above it came or went. If that card has gone (the Title left Continue watching meanwhile), focus goes to the card now in its place, or to the row that takes its place, like a removed Favourite. There is no Live tab until the sports hub (M3): Channels are reached through Favourites and Search (ADR 0007). Cards sit `card-gap` (20 dp) apart, enough that a 1.12x focused card never touches its neighbours.

**Movies and Shows.** The same rows as Home (`RowsScreen`), starting 16 dp under the tabs as Home's do (Sync now, the Sync status line and the Title counts are in Settings' Catalog section): the kind's Top row in the Region (`RankedCard`s, never looping), "Trending movies this week" (or shows), then "New movies" (or "New shows"), with a row per genre to follow (#200). Each tab shows a Title once on its own, so a Title on Home's Top row is on the tab's too. There is no grid: Titles not in a row are found through Search. While the first Sync runs, its progress shows centred where the rows will be; if it fails with no catalog stored, why it failed shows there in the error colour.

**Details.** Same backdrop. A header column (470 dp) holds the display name, metadata line with badges (a Top 10 Title's rank first, as a solid badge; the badges go under the line when both don't fit, so the line is never cut for them), three lines of description, and the people in muted body. On a Show, a title-type line between the metadata and the description names the Episode Play starts ("S1 E6 · The Dover Job"). Below it, the action list: rows of one width (360 dp, inside the header column), 4 dp apart. Its first row is the primary capsule (`PrimaryAction`), naming exactly what it plays ("Continue from 1:02:13", "Continue S1 E6", "Play S1 E1", "Play") with a 48 dp progress bar inside it once the Viewer has stopped partway. Under it are the other actions (`ActionRow`): "Restart", and "Remove from Continue watching" on a Movie. A muted label naming the Version Play will start sits under the list. Next comes a "Versions" headline with the `CertaintyLegend` on the right, the `VersionHeader`, then Versions grouped by language ("English · 3"), 8 dp between rows. A `SettingRow` at the end reveals other languages.

**Player overlay.** A top scrim and a bottom scrim over the picture. Top left: the Title name (headline), the playing Version's badges and its audio/Source in caption. Top right: the one-tap buffering suggestion. Bottom: elapsed time, a 6 dp scrubber (white at 25% track, white at 45% buffered, white played), remaining time, and a row of glass buttons. Both times are label type in tabular digits (`tnum`), each in a slot as wide as the longest time the film can show, so the scrubber never moves as the seconds tick. Under the scrubber, the transport sits centred on the screen as round `NuageIconButton`s (Back 10 s, Play/Pause a step bigger at 52 dp, Forward 10 s, `xl` apart), between two labelled capsules level with Play/Pause: Versions (list icon) at the left and Audio and subtitles (subtitles icon) at the right. Both on one side would push the transport off centre. Should a label ever reach the middle, the transport gives way to stay `xl` clear of it.

**Search.** A 290 dp left column holds the query field, a caption hint, the `RemoteKeyboard` and, 14 dp under it, `Completions`. From two letters these are up to seven queries one press away, as Netflix offers them: Title names (Content languages first), then people, then genres, from the search index. Before anything is typed they are genre shortcuts, the genres with the most Titles in the Viewer's languages. OK on one replaces the query and moves focus to the first result. The column scrolls once focus goes down into them, and back to the top once focus returns to the keyboard, so the query is always in view while typing. Each key edits the query as it is when the key lands, so presses faster than the stick redraws aren't lost. Results sit 40 dp to its right, grouped as "Movies · n" and "Shows · n" with poster cards (a Title in the Region's Top 10 shows its rank, "#1 in movies in Canada" or "…worldwide", as a solid badge alone under the focused card, running on past the poster's right edge since it is wider than a poster (138 to 181 dp); the Version badges wait for the details), then a row per genre found, headed by its name, then "Channels · n" with `ChannelCard`s. When the query is a genre's whole name (a picked genre completion or shortcut, or the name typed in full), that genre's row comes first and takes the first-result focus, as on Netflix: the Viewer chose a genre, not a name. Every row starts `m` further left, so a focused card isn't clipped by the column's edge. Search answers twice, with the device's own matches and again once TMDB's search adds what it knows; each answer shows every row from its start, at its best match, except a row focus is in, which keeps the focused card on screen. Focus still on a row's first card, where picking a completion put it, moves to the new first card.

**Settings.** A single column 760 dp wide, 8 dp between rows, grouped under headline headings. Exclusive options use a label column (320 dp) with a `ChoiceRow` beside it. `SettingsScreen` is the last of the `TopTabs`: App language, Quality cap, Background reading and Play next Episode (On / Off) are `ChoiceRow`s, and Content languages and Region are `SettingRow`s that each open a list of `SettingRow`s in place of the column, the chosen ones marked with a check; Back returns focus to the row that opened it. Changing the App language recreates the screen in the new language, focus back on its row. An "App" heading closes the column with an "App version" `SettingRow`: the installed version ("0.1.95 · Up to date"), or "Update to 0.1.96", which starts the update.

**Update.** When a newer release exists, the `UpdateScreen` comes before everything else on launch, centred on the bare ground in a 560 dp column: a headline, a muted body line, then capsule buttons 12 dp apart, the main one focused ("Update now", then "Later"). The same screen carries each step: the first update's guide to allowing installs ("Open settings", with the Fire OS path in muted caption below), the download with a 6 dp bar drawn like the player's scrubber, and "Install" to reopen Android's installer. Failures are Failure Red body text with "Try again". Back and "Later" go on to the app until the next launch.

**EdgeScroll.** `NuageTheme` provides `EdgeScroll` as the `LocalBringIntoViewSpec`, so every lazy list in the app gets it, except Home's rows (above). Compose for TV's default keeps the focused item at a fixed pivot, which moves the screen on every press. EdgeScroll leaves the list still while the focused item sits between 10% of the viewport from the leading edge and 22% from the trailing edge. When focus leaves that band, the list scrolls just enough to put the item back on the nearer line. The trailing band is larger because a card's name and badges appear below it. An item too big for the band aligns to the leading line.

### Named Rules
**The Still Screen Rule.** Lists don't move on every press. Don't override `LocalBringIntoViewSpec`, and don't scroll to a pivot. Rely on EdgeScroll. A list may open somewhere other than its top once, when the screen opens (a Show's details open on the next Episode); from then on only EdgeScroll moves it. Home is the one exception: its rows keep focus at one place, the left margin and the top of the rows, and move under it on every press, as Netflix's do, with the focused row's info under it and its long rails looping.

**The Overscan Rule.** Nothing readable or focusable sits outside 48 x 27 dp from the screen edge at rest. Only artwork and scrims go full bleed.

## Elevation & Depth

Depth comes from three things: artwork behind, translucent glass on top, and one lift for focused artwork. Glass is flat translucent white; there is no blur anywhere in the app. The only shadow belongs to a focused card.

### Shadow Vocabulary
- **Card lift** (Compose `shadow(28.dp)`, black ambient and spot, roughly `0 14px 28px rgba(0,0,0,0.6)` in CSS): only under a focused `TitleCard`, drawn by `FocusSurface` with `FocusWeight.Card`.
- **Sheen** (linear gradient from white at 30% to transparent at 45% of the diagonal): the light catching a lifted card, drawn over its artwork.
- **Rim** (1.5 dp border, white at 60%): keeps a focused card visible where the shadow disappears into the dark ground.

### Backdrop and scrims
`Backdrop` (in `ui/components/`) crops the artwork to fill the screen, then lays two scrims of `ground` over it:
- **Horizontal:** 85% at the left, 25% at 55% of the width, transparent at the right. The hero text sits on the dark side.
- **Vertical:** 85% at the top (under the tabs), transparent from 20% to 35%, 85% at 75% of the height, solid `ground` at the bottom. Rows sit on the dark band.

The player uses its own vertical scrim, darker at the top (85%, then 60% at 12%) and 85% at the bottom.

When glass sits on busy artwork and needs to carry text, as in the player's buffering suggestion, it first lays `veil` (`ground` at 75%) and then `glass` over it.

The player's Versions lay one `veil` under the whole list instead of one under each row: a slab with `card` top corners behind the headline row, `VersionHeader` and rows, `m` wider than the rows on each side, as tall as the Versions need up to 62% of the screen, and running off the bottom edge. Rows on separate veils would leave the moving picture flickering in the gaps between them, and the muted column names above the rows would stay on the thin top of the scrim. The details page's Versions keep no veil: there they sit on the backdrop's own dark band.

### Named Rules
**The Tint, Not Blur Rule.** Glass is a translucent tint (`glass`, `glass-high`), not runtime blur. The Fire TV Stick can't afford a blur, and focus moves must stay instant.

**The One Lift Rule.** Only focused artwork lifts. Buttons, tabs, keys and rows never cast a shadow.

## Shapes

Corners are soft everywhere and there are no sharp rectangles. Every control is a capsule. A 40 dp button with `control` (22 dp) corners reads as a pill, and the tab bar and choices use fully rounded ends. Posters, stills and text fields have `card` (12 dp) corners. Badges are small rounded rectangles (`badge`, 4 dp) so they read as labels, not buttons. Version rows and setting rows take `control` corners, which makes them long pills. Artwork is always clipped to its corner shape, and the focus rim follows the same shape.

## Components

All focus feedback comes from `FocusSurface(weight = …)`. Never draw focus by hand.

### Focus grammar
- **`FocusWeight.Card`** (artwork): scales to 1.12x over 160 ms, lifts on the card shadow, gains the sheen and rim. The container doesn't change.
- **`FocusWeight.Control`** (buttons, tabs, keys, choices): scales to 1.06x over 160 ms and the container turns `focus` white with `on-focus` black content.
- **`FocusWeight.Row`** (anything that spans a list): does not scale, so its edges stay inside the overscan margin. Choose by content:
  - **White fill** when the row holds only label, detail and value text (`SettingRow`). All text switches to `on-focus`.
  - **White ring** (2 dp `focus` border on `glass-high`) when the row holds badges or several columns whose colours carry meaning (`VersionRow`, Movies list rows, `NuageTextField`), so the badges and muted cells stay readable.

### Buttons (`NuageButton`)
- **Shape:** capsule, 40 dp tall, 18 dp horizontal padding, optional 20 dp leading icon 8 dp before the label.
- **Rest:** `glass-high` with `ink` label.
- **Focus:** `focus` fill, `on-focus` label and icon, 1.06x.
- **Disabled:** stays glass with an `ink-muted` label, still focusable, and ignores the click.

### Action list (`PrimaryAction`, `ActionRow`)
- A details page's actions, stacked as rows of one width, each 40 dp tall with a 20 dp leading icon 8 dp before a label-type label, 18 dp in from the start.
- **`PrimaryAction`:** `glass-high` capsule at rest, naming what it plays. Its progress, when there is any, is a 48 x 4 dp bar after the label: `ink` over `ink` at 30%, `on-focus` over `on-focus` at 30% when focused. Disabled as a `NuageButton`.
- **`ActionRow`:** no container at rest, just the white icon and label.
- **Focus:** `FocusWeight.Row` with a white fill, so neither row grows and the list keeps its width.

### Icon buttons (`NuageIconButton`, `NuageIcons`)
- **Shape:** a `glass-high` circle, 44 dp by default, holding a white icon half its size. `slot` centres a smaller button in a bigger one's height so a row lines up.
- **Focus:** `focus` fill, `on-focus` icon, 1.06x, and the button's name in caption `ink` 8 dp under it. The caption slot is always reserved, so nothing moves as focus does.
- Only for the player's transport, where the icons are universal. Anything less obvious keeps its words (a `NuageButton` with a leading icon).
- Icons are Material Filled, solid white shapes like the launcher's z. `material-icons-core` covers most; the few others (Pause, Replay 10, Forward 10, Subtitles) are copied into `NuageIcons` rather than pulling in `material-icons-extended`.

### Navigation (`TopTabs`, `Wordmark`)
- The Wordmark ("zugig", lowercase like the TV banner, ending in a mid-grey `#77787E` dot raised to cap height) sits left. The tabs sit centred in a `glass` capsule with a 1 dp white-at-14% hairline and 4 dp inner padding, 4 dp between tabs.
- Home, Movies and Shows are words. Search and Settings are round icon tabs at the end (magnifier, gear): 32 dp circles holding a 20 dp icon, named by its content description.
- **Tab:** 32 dp tall, every one alike; label type centred with 14 dp padding at the sides. **Selected:** `glass-high` with `ink`. **Inactive:** transparent with white at 85%. **Focus:** `focus` fill, `on-focus` text, 1.06x.

### Cards (`TitleCard`)
- **Poster:** 136 dp wide, 2:3: about 5.5 across Home's rows between the margins, `card-gap` apart. **Wide:** 196 dp wide, 16:9, for Continue watching.
- **Corner Style:** `card`. **Background:** the artwork itself, cropped to fill.
- **Progress:** a 4 dp bar along the bottom edge, `ink` over `ground` at 60%.
- **Focus:** `FocusWeight.Card`. Under the card, after 12 dp, a 44 dp slot shows the Title name (title type, one line) and a compact `BadgeRow` only while the card has focus. The slot is always reserved so rows don't jump.
- **Info under the rail (Home, `RailInfo`, `CardInfo`):** on Home the cards reserve no slot and show no name; the info under the focused row tells about the focused card instead, as Netflix's does, at the left margin and 500 dp wide. It never names a Title: the artwork and its logo do. For a Title not started: the metadata line (year · runtime · genres, `rail-meta`, muted) with the compact badges, then up to three lines of description (`rail`, ink), 4 dp under it, 101 dp tall in all. In Continue watching, two lines and no badges: a Movie's name, or the Episode a Show is at ("S1 E7 · Episode 7"), in `rail`, then in `rail-strong` the time left ("44 min left"), or "Start next episode" when the Viewer Watched the one before. For a Channel, its name (`rail`) and the menu key's hint (`rail-meta`, muted). Continue watching and Favourites keep 50 dp for their two lines. The info keeps the last card's while focus is on the tabs.
- **Top row card (Home, `RankedCard`):** Netflix's Top 10 look. The rank, 1 to 10, is a big outlined numeral beside the poster, and the poster overlaps its right edge by `numeral-overlap` (12 dp); the numeral shows `numeral-width` (72 dp) to the poster's left, so a Top row's card is 208 dp wide, `card-gap` apart. The numeral is Inter Bold at 200 sp (`Type.numeral`), its digits about three quarters of the poster's height, centred on the poster: the middle of the digits' drawn glyph (measured from the glyph itself, not the line, which keeps more room above the digits than below) sits level with the poster's middle. It is narrowed to 72% of Inter's width (Inter has no condensed cut). A two-digit 10 narrows further, to fit the same room, so it never reaches the card before; about half its 0 hides under the poster, as on Netflix. The outline is `ink-muted`, 3.5 dp, drawn as a 7 dp stroke under a fill of `ground`, because Inter's digits are built from overlapping shapes whose inner edges would otherwise show (the 4's). The numeral never takes focus: the poster does, and grows over it at 1.12x, still 12 dp clear of the next card's numeral. The poster keeps the logo over textless art, as on every row.
- **Logo on the card (Home, `CardLogo`):** as on Netflix, a Home card's name is its Title's TMDB logo (the same one as on details, at `w300`) laid over art with no text on it, at its bottom left, `s` in from the edges, fitted in 80% of a poster's width and 25% of its height (50% and 40% on a still), over a scrim of `ground` from transparent to 70% across the card's bottom 45%, so light logos stay readable on bright artwork. A poster card uses TMDB's most voted textless poster (one with no language) when the Title has both that and a logo, and shows the logo on every card, focused or not; otherwise it keeps the regular poster, which carries its name, with nothing over it, so a name never shows twice. A still always shows the logo, or the name in title type without one. The logo loads like the card's artwork, once focus has rested 250 ms.

### Channel cards (`ChannelCard`)
- **Shape:** the wide card (196 dp, 16:9, `card` corners) on `surface` glass, the Provider's logo drawn whole (`Fit`, `l` padding), never cropped: logos come in every shape. Without a logo, the Channel's name centred in label type.
- **Focus:** `FocusWeight.Card`. The 44 dp slot shows the Channel's name (title type) and, in muted caption, what the menu key does: "≡ Add to Favourites" or "≡ Remove from Favourites". On Home there is no slot: the info under the rail shows both. OK watches the Channel at once; the menu key adds or removes the Favourite in place.

### Badges (`Badge`, `BadgeRow`, `CertaintyLegend`)
- **Confirmed:** solid `confirmed` fill, `on-confirmed` caption text. **Likely:** no fill, a 1.5 dp `likely` outline, `likely` text. The difference is in the shape, not only the colour.
- `BadgeRow` spaces badges 4 dp apart. `compact = true` (under cards) trims padding to 4 dp and spacing to 3 dp.
- `CertaintyLegend` explains the two styles ("read from the file" / "from the name only") wherever a screen first shows many Versions, for household members who didn't set the app up.

### Version table (`VersionRow`, `VersionHeader`)
- A `glass` pill, 14 x 10 dp padding, with fixed-width columns sized for French: Quality 188 dp, Source 124 dp, Audio 188 dp, Subtitles 120 dp, Bitrate taking the rest, right-aligned.
- Quality holds the badges plus an optional caption note: `ink-muted` normally ("Likely English"), `caution` for cautions ("Cinema recording (CAM)").
- Source is label type in `ink`. The other cells are muted detail type, two lines at most. Bitrate is detail type in `ink`, with its Backup count in muted caption below.
- `VersionHeader` names the columns in muted caption with the same widths. Group Versions under a title-type language heading.
- **Focus:** the white ring on `glass-high`. No scale.

### Settings (`SettingRow`, `ChoiceRow`)
- **SettingRow:** a `glass` pill, 16 x 12 dp padding. Label (label type) with optional detail (muted caption) on the left, current value (muted detail type) on the right. Focus is a white fill, with the detail at 70% black. An optional progress bar sits 6 dp under the label: 120 dp by 4 dp, a capsule, `ink` over `ink` at 25% (black over black at 25% when focused). A disabled row, like a disabled button, keeps focus, mutes its label and ignores the click.
- **ChoiceRow:** capsules 36 dp tall, 8 dp apart. A choice's text stays on one line; when the choices don't fit beside their label, whole capsules move to a second line, 8 dp below. **Unselected:** transparent with a 1.5 dp `ink-muted`-at-50% outline. **Selected:** `glass-high` with a 16 dp check. **Focus:** white fill. Only for small exclusive sets (Quality cap, Background reading).
- **Catalog section** (before App): one `SettingRow`, "Sync now", whose value is the Sync status line as the tabs used to word it (running with its progress, synced with how long ago, matching, failed in the error colour), right-aligned on up to two lines; its detail is the Title counts ("12,345 movies · 3,210 shows"). OK starts a Sync; the row is disabled while a Sync runs or Matches. A failed Sync with a catalog stored shows only here.
- **Region list:** every country as a `SettingRow`, the chosen one marked ✓, in two groups under headings. **Suggested** (at most 8): the current Region, which takes first focus, the device's country, then the main countries of each Content language in order, each once. **All countries** follows, sorted by name with the App language's collator. A letter rail sits at the left, 16 dp from the list: one 36 dp round `FocusWeight.Control` key per letter that has countries, `ink-muted` on the bare ground, white fill on focus, 2 dp apart, scrolling with focus. A country's letter is its name's first letter without accents, so "États-Unis" is under E. Left from any country moves focus to its letter; Up and Down move between letters; OK or Right jumps to the letter's first country. Back returns to the Region row.

### Inputs (`NuageTextField`, `RemoteKeyboard`, `Completions`)
- **NuageTextField:** a caption label above it (`ink-muted`, `ink` when focused), then a 48 dp `glass` field with `card` corners and 16 dp padding, body text, an `ink` cursor and a muted placeholder. **Focus:** the white ring on `glass-high`. D-pad up and down leave the field, and the IME action moves to the next field or submits on the last one.
- **RemoteKeyboard:** 34 dp capsule keys on `glass`, 6 dp apart, letters a–z then digits on a 7-column grid in alphabetical order, so the layout is the same in English and French. Space and Delete are wide keys. Keys use `FocusWeight.Control`.
- **Completions:** plain text rows under the keyboard, body type on the bare ground, 12 x 5 dp padding, 2 dp apart, one line each. `FocusWeight.Row` with a white fill.

### Real screens today
`LoginScreen` (Provider login, on the bare ground), `HomeScreen` (the Home tab: Continue watching as wide stills with progress bars, New on your service, then a poster row per TMDB genre, laid out as in Home above but without the featured hero yet; the info under the focused rail shows where the Viewer is in Continue watching, the metadata line and description elsewhere; long rails loop, and cards show their Title's logo over textless art), `TitlesScreen` (the Movies and Shows tabs: Home's rows through the shared `RowsScreen`, as in Movies and Shows above) and `TitleScreen` (a Title's details: header, the action list with a muted label under it naming the Version Play will start, `CertaintyLegend`, `VersionHeader`, Versions grouped by language, and an "Other languages" `SettingRow` that opens the remaining groups beneath it) are the shipped surfaces. Play takes first focus; with nothing the TV can display it is disabled and the label says so, and focus starts on the first Version, whose row plays that Version. On a Movie in Continue watching, Play reads "Continue from 1:02:13" with its progress inside, and "Restart" and "Remove from Continue watching" are rows under it. Remove puts focus back on Play, now from the beginning. A Show's Play comes once its Episodes are read and plays the Best playable Version of its next Episode (below): "Continue S1 E6", from where the Viewer stopped, with its progress and a "Restart" row; "Continue S1 E7" once they Watched E6; or "Play S1 E1" when they never played the Show. The header names that Episode. A Show's details put an "Episodes" headline above its Versions: a title-type heading per season ("Season 4 · 4 Episodes"), then one `SettingRow` per Episode with its number and name on the left and the languages it's available in on the right, the Viewer's first; choosing one plays its Best playable Version, from where the Viewer stopped in it. A started Episode shows its progress bar under its name, full once Watched. A Show's Play takes first focus from the first Version once the Episodes are read, and Down from the last action goes to the next Episode to watch (the one the Viewer stopped in, else the one after the last Watched, else the first). With nothing to play, first focus goes to that Episode instead. Either way the list opens so the Episode before it peeks at the top edge of the screen, in the overscan margin; until the Episodes are read, focus waits on the first Version, and it stays wherever the Viewer moved it meanwhile. Episodes TMDB lists as airing later that no Version has yet follow the others, named as TMDB names them, with "Available October 31" (in the App language, with the year when it isn't this one) in place of their languages; their rows are disabled. `PlayerScreen` plays full screen under the player overlay above, which replaces Media3's stock controls on both engines. Any key shows it. Left and right on the bare picture, and rewind and fast-forward at any time, scrub a Movie or Episode: the picture pauses and only a 14 dp white playhead moves along the scrubber, the played part following it, with its time above it in label type (tabular digits, kept within the bar). A tap moves it 10 s; holding speeds it up, to the whole film in about 2.5 s after the first 1.5 s. OK, or the remote resting 1 s, seeks there once and plays on; Back returns to where playback was. A Channel has no scrubbing; Play/Pause takes first focus between the round "Back 10 s" and "Forward 10 s", and it hides after 5 s without a key press unless paused, or on Back. Its top names the Title (with "S1 E2" for an Episode), the Version's badges and its play label. Without it, the Version starting or just started is named (label, with a caption hint "≡ Versions") under the top scrim. The buffering suggestion shows top right, takes focus so one press switches, and steps aside on Back or after 15 s. A file Media3 can't open moves to the VLC engine by itself. The remote's menu key, or the overlay's "Versions", opens the Versions over a bottom scrim, on one `veil`: a headline row naming the player ("Playing with VLC") beside "Try other player", which takes first focus and resumes on the other engine at the same position, then `VersionHeader` and one `VersionRow` per Version, the one playing (or whose Backup is playing) noted "Playing". "Audio and subtitles" lays four cards along the bottom over the same scrim, `m` apart and sharing the width: Audio, Subtitles, Subtitle style and Delays, each glass on `veil` with `card` corners, its name in muted caption over what is on now in label ("Original: English · 5.1 · Dolby Digital Plus", "Off", "Medium, White, Shadow", "None"), so everything shows before any list opens. Audio takes first focus and Left/Right move between the cards. OK or Up opens the focused card's list upward on one `veil` slab, over its own card where it fits (440 dp for Tracks, 620 dp for the style, 520 dp for the delays) and kept within the bottom half of the screen, so the picture's top half stays clear; focus lands on what is on. Audio lists `SettingRow`s for the audio Tracks, the one in the Title's original language (TMDB's `original_language`) named "Original: …"; Subtitles lists "Off", the subtitle Tracks and any downloaded ones; the one on is noted "Playing", and Left/Right there open the card beside it. Subtitle style has a 160 dp label column and a `ChoiceRow` each for size, colour, background and position; Delays has "Earlier" and "Later" to move the audio delay 50 ms or the subtitle delay 250 ms a step. Down out of a list, or Back, closes it; Back again closes the cards. Picks are remembered for the next file, and the style is kept. The suggestion never shows over an open list. When nothing plays, the error offers "Try other player", focused, and the menu key still opens the Versions. An Episode's transport adds a round "Next Episode" (Skip next) after "Forward 10 s". From its credits (the file's last 10%, or last 10 minutes when that is less, the same rule that makes a Movie Watched) a card sits bottom right over the bottom scrim, glass on `veil` with `card` corners: "Next: Season 1, Episode 3" (label), "Plays in 10 s" (muted caption) while it counts down, then "Play now", focused, and "Watch credits". It hides the overlay and holds back the buffering suggestion; "Watch credits" or Back turns it away until the Viewer seeks out of the credits. The countdown runs 10 s unless Settings' "Play next Episode" is Off, or three Episodes have started by themselves since a key was last pressed. When an Episode ends, "Next: Season 1, Episode 3", the countdown if it still runs, and a focused "Play next Episode" sit centred on the ground; Back leaves. `SearchScreen` follows the Search layout: the query, a caption hint, the `RemoteKeyboard` and `Completions` on the left (the keyboard takes first focus), and results on the right as "Movies · n" and "Shows · n" rows of poster cards, a row per genre found, Channels, then one row per person found, headed by their name. It is the last of the Home, Movies and Shows tabs. Home's featured hero exists only as a debug prototype with placeholder content.

Names, posters, descriptions, genres and cast come from TMDB in the App language, falling back to English. Details put TMDB's backdrop behind everything (`Backdrop`, with the scrims below) and show the description (3 lines) and the first four cast names under the metadata line. The TMDB attribution notice closes the details list, in muted caption, whenever TMDB data is on screen. Without TMDB metadata (an Unmatched Title, or the cloud out of reach), artwork is the Provider's poster and a card without one is glass with the Title's name on it. Details then show the poster beside the header instead of as a backdrop, because a poster blown up to the full screen is too blurry and too bright to keep text on it readable. Language names in Version lists are written in their own language ("Deutsch", "Français (Québec)") so each household member finds theirs.

### Running the prototypes
Debug builds include `PrototypeActivity`:

```
adb shell am start -n dev.betteriptv.debug/dev.betteriptv.app.prototype.PrototypeActivity --es screen <home|details|player|search|settings> [--ez focus true] [--ez tracks true]
```

`focus true` moves first focus from the main button to the first card (home) or the first Version (details); `tracks true` opens the player's Audio and subtitles cards. Prototypes carry a "Sample content" caption so nobody takes the made-up Versions for real ones. The design has only been checked on the emulator; checking it on the Fire TV Stick is tracked in victortrinh/better-iptv#23.

### Known gaps (open, for later screen tickets; not rules)
- Glass sits on bare black on screens without artwork (search, settings, login, Movies), so it reads as flat grey there.
- The sheen on a focused card is static.
- There is no authored motion moment beyond the 160 ms focus scale.
- The featured Title is a single still.

## Do's and Don'ts

### Do:
- **Do** read every colour, size, corner and gap from `Colors`, `Type`, `Corners`, `Spacing`, `Cards` and `FocusScale`. Screens never hold raw values.
- **Do** make every focusable thing a `FocusSurface`, or a component built on it, and pick its weight: Card for artwork, Control for buttons/tabs/keys/choices, Row for anything spanning a list.
- **Do** show Confirmed as a solid badge and Likely as an outlined badge everywhere a Version attribute appears: hero, card, details, player.
- **Do** put a full-bleed backdrop with `ground` scrims behind any screen that has a Title's artwork, and keep text on the scrimmed side.
- **Do** keep content inside the 48 x 27 dp overscan margins, applied as list `contentPadding`.
- **Do** check every text colour for 4.5:1 against what is behind it, including glass over artwork.
- **Do** size fixed widths for French strings about 20% longer, and cap single lines with an ellipsis.
- **Do** build on `androidx.tv:tv-material` (`Text`, `Icon`, `MaterialTheme` mapped by `NuageTheme`) and the components in `ui/components/`.

### Don't:
- **Don't** add colour to the interface. No accent hue, tinted glass or coloured focus. Colour comes from artwork, Caution Amber marks cautions, and Failure Red marks failures, as text.
- **Don't** fill anything solid white unless it has focus.
- **Don't** use runtime blur (`Modifier.blur`, RenderEffect) for glass. On the Fire TV Stick glass is a translucent tint.
- **Don't** use Compose Material 3 phone components (`androidx.compose.material3`: Card, Button, TextField, NavigationBar and the like). They bring touch ripples, phone sizes and their own focus.
- **Don't** scale list rows on focus. They get a white fill or a white ring.
- **Don't** show a card's name or badges unless it has focus, and don't let the slot under a card collapse.
- **Don't** override EdgeScroll or scroll lists to a fixed pivot, outside Home's rows.
- **Don't** give buttons, tabs or rows a shadow. The lift belongs to focused artwork.
