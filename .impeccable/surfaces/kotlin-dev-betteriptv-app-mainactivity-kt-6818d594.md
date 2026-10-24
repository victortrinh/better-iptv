---
version: 1
slug: "kotlin-dev-betteriptv-app-mainactivity-kt-6818d594"
primary_target: "app/src/main/kotlin/dev/betteriptv/app/MainActivity.kt"
related_targets: ["app/src/debug/kotlin/dev/betteriptv/app/prototype/HomePrototype.kt"]
---

Scope: the whole Android TV app shell (home rows, Title card, details with Versions, player overlay, search, settings, login, Movies). Mode: Operate.
Audience: the Viewer and household, on a couch with the Fire TV remote, dim room. Job: pick something and start it.
Constraints: D-pad only; Fire TV Stick is weak; Confirmed vs Likely must look different everywhere; English now, French later.

## Direction contract
THESIS: Verre. The Apple TV app (tvOS Liquid Glass) on a Fire TV: the artwork is the interface. Refuses the category default of a grey streaming grid with a coloured accent and the Netflix black-and-red.
OWN-WORLD: near-black ground (#07080A); everything else is white and translucent white glass (12% and 20%); a full-bleed backdrop with scrims; Inter throughout, bold tight display; capsule controls; 12 dp poster corners; the only solid white is what has focus. Colour comes only from artwork, plus amber for cautions (CAM, Audio description).
STORY: the household sees one film big, knows what it is and how good the copy is (badges), and presses Play; rows below offer more. Details show every Version as a table grouped by language, with solid Confirmed and outlined Likely badges and a legend.
FIRST VIEWPORT: wordmark top left, glass tab capsule centred at the top; featured Title's backdrop fills the screen; title at 50 sp bottom-left third with meta line, badges, two lines of description, Play and Details capsules; the Continue watching row (16:9 stills with progress) peeking at the bottom.
FORM: Viewer-steered "more Apple" after a seven-direction round (seed f07ccd8c, canon path, Viewer pinned Apple TV as the reference). Signature move: focused cards lift 1.12x on a deep shadow with a light sheen and bright rim; names and badges appear only under the focused card.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
