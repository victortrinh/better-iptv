---
status: superseded by 0018 (2026-11-08)
date: 2026-11-08
---

# The Title treatment is cut from the Provider's poster, on the device

Amends [ADR 0014](0014-no-tmdb-api-titles-keep-the-providers-number.md), which left a Title named in type everywhere a logo was.

The hero and the Title page need the Title's name over its backdrop. A name set in the interface's own type read as a label, not a title, and the Viewer rejected the look. TMDB's logos are gone (ADR 0014). fanart.tv forbids commercial use of its API without written consent, and a project key alone doesn't clear a paid app; TheTVDB licenses its API by negotiation with no public price, or at 11.99 USD a year per Viewer. Both are a fourth external source for one picture, and the household would rather lose a feature than add a source.

The poster already carries the title as the studio set it: 91% of movie posters and 86% of show posters have printed text (Survey B). So the device cuts the **Title treatment** out of the poster: a text recognizer finds the blocks of text and the tallest that reads as the Title's name is taken (else the tallest), the crop is colour-keyed against its own border colour with an Otsu threshold, its specks far smaller than the letters are dropped, and the result is a transparent image of the studio's lettering. Three checks decide whether it is shown: the recognised text must hold at least half the Title's name words, the kept pixels must not be dark (luma 0.35 or more), and the key must keep between 3% and 70% of the crop. A cut-out that fails any check is not shown, and the Title is named in Display type as before.

Survey C (2026-11-08, 60 posters: the 20 most-listed Titles, 20 newest, 20 at random): by eye, 36 cut-outs were clean, 11 usable with a small flaw and 13 failed (five dark titles, four crops of a badge or credits, two titles cut in two, one busy background, one poster without text). The checks accept 45 and reject 15: three of the accepted are wrong (a title cut in two, a busy background, the Provider's 4K badge in the crop) and six of the rejected were fine (lettering the recognizer can't read, a poster in another language than the name). About three heroes in four get the studio's lettering; the rest keep the typed name.

## Considered

- **The typed name alone** (ADR 0014 as written): consistent and free, but it looks like a label on every hero. Kept as the fallback.
- **The poster beside the typed name on the hero** (Apple TV's layout): puts the studio's lettering on screen, but prints the name twice and changes the hero's layout from the backdrop-and-text block the rest of the app is built on.
- **The crop without the key** (a rectangle of poster): a slab of the poster's background on bright posters; a logo only when the poster is dark.
- **fanart.tv or TheTVDB logos**: licence questions and a new source, for a better-curated version of what the poster already holds.
- **Apple's subject-lifting model** instead of the colour key: finds no subject in text on four posters of six, and cuts letters on a fifth.

## Consequences

- The app carries a text recognizer: ML Kit's bundled text-recognition model, since Fire OS has no Google Play services. A few megabytes in the APK, run once per Title when its hero or page first needs it, and never on focus. The cut-out is kept with the Title as a PNG with alpha. Its cost on the Fire TV Stick is measured before it ships (a later issue).
- The treatment is in whatever language the chosen poster is, so the poster pick puts the App language's Listing first.
- `TitleName` draws the treatment where one is accepted, capped in height and width to the slot the Display name used, else the name in Display type. The starting cover keeps the typed name.
- About one cut-out in fifteen that passes the checks is wrong (3 of 45 in Survey C), one Title in twenty overall. The Viewer can't fix it; a later rule may.
- Nothing is fetched from anywhere new. The poster was already loaded; the text recognizer runs on the device; the cloud sees nothing.
