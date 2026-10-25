---
name: NuageTivi
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
    fontSize: "50px"
    fontWeight: 700
    lineHeight: "52px"
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "Inter"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: "26px"
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Inter"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "22px"
  body:
    fontFamily: "Inter"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: "22px"
  label:
    fontFamily: "Inter"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: "20px"
  caption:
    fontFamily: "Inter"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: "16px"
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
    width: "104px"
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

# Design System: NuageTivi

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

**The White Means Focus Rule.** No control or row is ever filled solid white unless it has focus. Two small indicators are exempt: the white progress bar on a card, and the off-white `confirmed` badge.

## Typography

**Display Font:** Inter 700
**Body Font:** Inter 400 and 600
**Label/Mono Font:** Inter 600 (no second family)

**Character:** One family, three weights (400, 600, 700, bundled as `inter_*.ttf`). The display size is bold and tracked tight so a Title's name reads like a poster credit. Everything else is plain and even, built for reading from a couch.

### Hierarchy
- **Display** (`Type.display`): a Title's name in the home hero and at the top of its details page. Two lines at most.
- **Headline** (`Type.headline`): row headings ("Continue watching"), screen and section headings ("Versions", "Playback"), the player's Title name. The Wordmark uses it at 22 sp.
- **Title** (`Type.title`): a Title's name under a focused card, the language group heading over Versions ("English · 3"), the search query.
- **Body** (`Type.body`): descriptions, metadata lines, Version cells, setting values, field text. Descriptions are capped at 2 lines in the hero and 3 on details.
- **Label** (`Type.label`): buttons, tabs, choices, keys, setting labels, the Source cell of a Version row.
- **Caption** (`Type.caption`): badges, column headings, field labels, explanations under a setting, the Sync status line.

### Named Rules
**The Readable From the Couch Rule.** All text keeps at least 4.5:1 contrast against what is actually behind it. That includes glass over artwork, so for example inactive tab labels use white at 85%, not `ink-muted`. Nothing goes smaller than caption (12 sp).

**The French Is Longer Rule.** French strings run about 20% longer. Fixed widths (Version columns, the settings label column, buttons in a row) must hold the French string, and single-line text gets `maxLines` with an ellipsis instead of overflowing.

## Layout

**Overscan-safe margins.** Every screen keeps its content inside `screen-x` (48 dp) left and right and `screen-y` (27 dp) top and bottom. Scrolling lists apply the margin as `contentPadding` rather than outer padding, so cards scroll under the edge and are never clipped while focused. Login doubles the horizontal margin (96 dp) to centre a two-column form.

**The shell.** `TopTabs` runs across the top inside the margins: the Wordmark on the left and the tab capsule centred. Tabs are Home, Movies, Shows, Live, Search, Settings. The tabs are the first item of the scrolling column, so they scroll away once focus goes down into rows.

**Home.** The featured Title's backdrop fills the screen. The hero text column (440 dp wide) starts 96 dp below the tabs, at the bottom-left third. In order: display name, a metadata line (year · runtime · genres in muted body) followed by its badges, two lines of description, then Play and Details buttons 12 dp apart. Each row follows: a headline heading, 12 dp, then a `LazyRow` with `screen-x` horizontal and 10 dp vertical content padding, then 14 dp before the next heading. Posters (2:3, 104 dp) fill most rows. Continue watching uses 16:9 stills (196 dp) with a progress bar, so the Viewer sees where they stopped. Cards sit `card-gap` (20 dp) apart, enough that a 1.12x focused card never touches its neighbours.

**Details.** Same backdrop. A header column (470 dp) holds the display name, metadata line with badges, three lines of description, and the people in muted body. Below it, Play sits next to a muted label naming the Version Play will start. Next comes a "Versions" headline with the `CertaintyLegend` on the right, the `VersionHeader`, then Versions grouped by language ("English · 3"), 8 dp between rows. A `SettingRow` at the end reveals other languages.

**Player overlay.** A top scrim and a bottom scrim over the picture. Top left: the Title name (headline), the playing Version's badges and its audio/Source in caption. Top right: the one-tap buffering suggestion. Bottom: elapsed time, a 6 dp scrubber (white at 25% track, white at 45% buffered, white played), remaining time, and a row of glass buttons. Transport is on the left and Versions / Audio and subtitles are on the right.

**Search.** A 290 dp left column holds the query field, a caption hint and the `RemoteKeyboard`. Results sit 40 dp to its right, grouped as "Movies · n" and "Shows · n" with poster cards.

**Settings.** A single column 760 dp wide, 8 dp between rows, grouped under headline headings. Exclusive options use a label column (320 dp) with a `ChoiceRow` beside it.

**EdgeScroll.** `NuageTheme` provides `EdgeScroll` as the `LocalBringIntoViewSpec`, so every lazy list in the app gets it. Compose for TV's default keeps the focused item at a fixed pivot, which moves the screen on every press. EdgeScroll leaves the list still while the focused item sits between 10% of the viewport from the leading edge and 22% from the trailing edge. When focus leaves that band, the list scrolls just enough to put the item back on the nearer line. The trailing band is larger because a card's name and badges appear below it. An item too big for the band aligns to the leading line.

### Named Rules
**The Still Screen Rule.** Lists don't move on every press. Don't override `LocalBringIntoViewSpec`, and don't scroll to a pivot. Rely on EdgeScroll.

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

When glass sits on busy artwork and needs to carry text, as in the player's buffering suggestion, it first lays `ground` at 75% and then `glass` over it.

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

### Navigation (`TopTabs`, `Wordmark`)
- The Wordmark (NuageTivi) sits left. The tabs sit centred in a `glass` capsule with a 1 dp white-at-14% hairline and 4 dp inner padding, 4 dp between tabs.
- **Tab:** label type, 14 x 6 dp padding. **Selected:** `glass-high` with `ink`. **Inactive:** transparent with white at 85%. **Focus:** `focus` fill, `on-focus` text, 1.06x.

### Cards (`TitleCard`)
- **Poster:** 104 dp wide, 2:3. **Wide:** 196 dp wide, 16:9, for Continue watching.
- **Corner Style:** `card`. **Background:** the artwork itself, cropped to fill.
- **Progress:** a 4 dp bar along the bottom edge, `ink` over `ground` at 60%.
- **Focus:** `FocusWeight.Card`. Under the card, after 12 dp, a 44 dp slot shows the Title name (title type, one line) and a compact `BadgeRow` only while the card has focus. The slot is always reserved so rows don't jump.

### Badges (`Badge`, `BadgeRow`, `CertaintyLegend`)
- **Confirmed:** solid `confirmed` fill, `on-confirmed` caption text. **Likely:** no fill, a 1.5 dp `likely` outline, `likely` text. The difference is in the shape, not only the colour.
- `BadgeRow` spaces badges 4 dp apart. `compact = true` (under cards) trims padding to 4 dp and spacing to 3 dp.
- `CertaintyLegend` explains the two styles ("read from the file" / "from the name only") wherever a screen first shows many Versions, for household members who didn't set the app up.

### Version table (`VersionRow`, `VersionHeader`)
- A `glass` pill, 14 x 10 dp padding, with fixed-width columns sized for French: Quality 188 dp, Source 124 dp, Audio 188 dp, Subtitles 120 dp, Bitrate taking the rest, right-aligned.
- Quality holds the badges plus an optional caption note: `ink-muted` normally ("Likely English"), `caution` for cautions ("Cinema recording (CAM)").
- Source is label type in `ink`. The other cells are muted body, two lines at most. Bitrate is body in `ink`, with its Backup count in muted caption below.
- `VersionHeader` names the columns in muted caption with the same widths. Group Versions under a title-type language heading.
- **Focus:** the white ring on `glass-high`. No scale.

### Settings (`SettingRow`, `ChoiceRow`)
- **SettingRow:** a `glass` pill, 16 x 12 dp padding. Label (label type) with optional detail (muted caption) on the left, current value (muted body) on the right. Focus is a white fill, with the detail at 70% black.
- **ChoiceRow:** capsules 36 dp tall, 8 dp apart. **Unselected:** transparent with a 1.5 dp `ink-muted`-at-50% outline. **Selected:** `glass-high` with a 16 dp check. **Focus:** white fill. Only for small exclusive sets (Quality cap, Background reading).

### Inputs (`NuageTextField`, `RemoteKeyboard`)
- **NuageTextField:** a caption label above it (`ink-muted`, `ink` when focused), then a 48 dp `glass` field with `card` corners and 16 dp padding, body text, an `ink` cursor and a muted placeholder. **Focus:** the white ring on `glass-high`. D-pad up and down leave the field, and the IME action moves to the next field or submits on the last one.
- **RemoteKeyboard:** 34 dp capsule keys on `glass`, 6 dp apart, letters a–z then digits on a 7-column grid in alphabetical order, so the layout is the same in English and French. Space and Delete are wide keys. Keys use `FocusWeight.Control`.

### Real screens today
`LoginScreen` (Provider login, on the bare ground), `TitlesScreen` (Movies and Shows `TopTabs`, then one `TitleCard` per Title in a poster grid, `card-gap` apart) and `TitleScreen` (a Title's details: header, Play beside a muted label naming the Version it will start, `CertaintyLegend`, `VersionHeader`, Versions grouped by language, and an "Other languages" `SettingRow` that opens the remaining groups beneath it) are the shipped surfaces. Play takes first focus; with nothing the TV can display it is disabled and the label says so, and focus starts on the first Version, whose row plays that Version. A Show's details have no Play yet and put an "Episodes" headline above its Versions: a title-type heading per season ("Season 4 · 4 Episodes"), then one `SettingRow` per Episode with its number and name on the left and the languages it's available in on the right, the Viewer's first. `PlayerScreen` plays full screen with Media3's stock controls until the player overlay (victortrinh/better-iptv#13) replaces them. Over them it names the Version starting or just started (label, with a caption hint "≡ Versions") under the top scrim, shows the buffering suggestion top right, which takes focus so one press switches and steps aside on Back or after 15 s, and opens the Versions over a bottom scrim on the remote's menu key: `VersionHeader` and one `VersionRow` per Version, the one playing (or whose Backup is playing) noted "Playing". It uses the player scrim; the suggestion never shows over the open Versions. Home, the player overlay, search and settings exist only as debug prototypes with placeholder content.

Names, posters, descriptions, genres and cast come from TMDB in the App language, falling back to English. Details put TMDB's backdrop behind everything (`Backdrop`, with the scrims below) and show the description (3 lines) and the first four cast names under the metadata line. The TMDB attribution notice closes the details list, in muted caption, whenever TMDB data is on screen. Without TMDB metadata (an Unmatched Title, or the cloud out of reach), artwork is the Provider's poster and a card without one is glass with the Title's name on it. Details then show the poster beside the header instead of as a backdrop, because a poster blown up to the full screen is too blurry and too bright to keep text on it readable. Language names in Version lists are written in their own language ("Deutsch", "Français (Québec)") so each household member finds theirs.

### Running the prototypes
Debug builds include `PrototypeActivity`:

```
adb shell am start -n dev.betteriptv/dev.betteriptv.app.prototype.PrototypeActivity --es screen <home|details|player|search|settings> [--ez focus true]
```

`focus true` moves first focus from the main button to the first card (home) or the first Version (details). Prototypes carry a "Sample content" caption so nobody takes the made-up Versions for real ones. The design has only been checked on the emulator; checking it on the Fire TV Stick is tracked in victortrinh/better-iptv#23.

### Known gaps (open, for later screen tickets; not rules)
- The hero should recede when focus enters the rows. Today it stays at full strength.
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
- **Don't** override EdgeScroll or scroll lists to a fixed pivot.
- **Don't** give buttons, tabs or rows a shadow. The lift belongs to focused artwork.
