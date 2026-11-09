---
status: accepted
date: 2026-11-08
---

# The hero and the Title page show the poster beside the text, with no backdrop and no Title treatment

Amends [ADR 0014](0014-no-tmdb-api-titles-keep-the-providers-number.md), whose look put the hero and the Title page on the Provider's backdrop, and supersedes [ADR 0016](0016-title-treatments-cut-from-the-providers-posters.md) and [ADR 0017](0017-the-text-recognizer-reads-a-cortex-a53-on-the-fire-tv-stick.md), which named a Title by lettering cut out of its poster.

Since ADR 0014 the hero and the Title page filled the screen with the Provider's backdrop under scrims, and since ADR 0016 named the Title with its Title treatment, cut from its poster on the device by a text recognizer and a colour key. The Viewer saw both on the TV on 2026-11-08, the day they shipped (0.1.770 to 0.1.775), and rejected the look: the backdrops are the Provider's 1280 × 720 stills, often a scene rather than key art, blown up to the screen and darkened to keep text on them readable, and the cut-outs vary from poster to poster, one in fifteen wrong (Survey C). The poster is the Provider's one piece of art that is reliably good at the size it is shown, and it already carries the studio's lettering.

So the hero and the Title page become a text column with the **poster** at the right, at a card's size (136 dp, 2:3), on the bare ground, and the Title is **named in Display type**. The starting cover shows the same poster, centred, with the name in type only for a Title without one. The treatment pipeline, its text recognizer and the backdrop checks come out.

## Considered

- **Keep the backdrop, drop the treatment** (name in type over the backdrop, ADR 0014 as written): the Viewer's objection was to the backdrop too.
- **Keep the treatment, drop the backdrop**: the lettering beside the poster prints the title twice, and the cut-outs' quality was part of the objection.
- **The poster blown up as the background**: too blurry and too bright to keep text readable; already rejected under ADR 0014.
- **A bigger poster on the hero** (taller than a card): possible later; a card's size is the one the rows and the page already share, so the picture is in memory everywhere and the three screens agree.

## Consequences

- One poster size everywhere a Title is shown: card, hero, Title page, starting cover. The hero's and the page's poster is loaded ahead at that size (`loadPoster`), so it is in memory on the first frame, as the backdrop was.
- `Library.featured` no longer requires a backdrop: any Title with a Version in the Viewer's languages, not in Continue watching, qualifies; a Movie whose details can't be read is passed over, so the hero always tells of its Title.
- The backdrop refusal (#475), its table and `MIN_BACKDROP_RATIO` go; `FeaturedTitle` and `TitleDetails` carry no backdrop. The Provider's backdrop links are still parsed and stored with the details, read by nothing.
- The app carries no text recognizer and no native code: ML Kit (a few megabytes of the APK), the `/proc/cpuinfo` redirect of ADR 0017 and the NDK build go. The cut-outs an earlier build kept in its cache (`cache/treatments`) are left to the system to reclaim.
- The Versions screen and the Episode picker sit on the ground, so `veil-deep` goes; glass reads as flat grey on every Title screen, as on Search and Settings.
- Issue #528 (measure the recognizer on the stick) is closed: nothing to measure.
- Lost: a Title's own lettering on the hero and the page, and any full-screen picture in the app. The hero reads as a row with one Title's poster and text, Apple TV style, rather than a billboard.
