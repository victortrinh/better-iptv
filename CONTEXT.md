# Zugig

A TV app that turns an Xtream Codes IPTV subscription into a fast, clean library: one card per film or show, the best copy picked automatically, real trending, and live games easy to find.

## Language

### Provider catalog

**Provider**:
The IPTV service the Viewer subscribes to, reached through an Xtream Codes login.
_Avoid_: Server, panel, playlist

**Connection limit**:
How many streams the Viewer's subscription may play at once, across all devices.
_Avoid_: Max cons, slots

**Listing**:
One entry in the Provider's catalog (a movie, a show or a channel) exactly as the Provider names and files it.
_Avoid_: Stream, item, entry

**Category**:
The Provider's section a Listing is filed under, such as "EN - NEW RELEASE" or "TOP MOVIES BLURAY (MULTI-SUBS)".
_Avoid_: Group, folder

**Prefix**:
The code at the start of a Listing's name, such as `4K-FR-HDR` or `NF`, that encodes language, quality or Source.
_Avoid_: Tag, label

**Prefix table**:
What the app knows of a Provider's naming, its Base table and its Provider table together: the mapping from Prefixes and Category hints to Version attributes, and the Clutter, Tier tag, quality word and Event time rules.
_Avoid_: Dictionary, naming rules

**Base table**:
The part of the Prefix table that holds the conventions most Providers share (`EN -`, `NF`, `VIP:`), shipped with the app and the same for every Provider.
_Avoid_: Default table, generic table

**Provider table**:
The part of the Prefix table that is one Provider's own: what its Prefixes mean beyond the Base table, as Learned lines and Hand lines. A Hand line wins over a Learned line, and both over the Base table.
_Avoid_: Override, custom table

**Learned line**:
A Provider table entry the cloud worked out from what Header reads of that Prefix's files Confirmed, once enough reads agree. It shows as Likely.
_Avoid_: Inferred rule, auto rule

**Hand line**:
A Provider table entry written by a person, for what reads can't teach (Clutter, Tier tags, a Source) or where reads disagree.
_Avoid_: Override, manual rule

**Multi-audio**:
What a Prefix learns when most of its files carry two or more audio languages: its Versions are Untagged, Likely in the Title's original language.
_Avoid_: Multilingual, dual audio

**Sync**:
Refreshing the device's copy of the Provider's catalog, in two Stages.
_Avoid_: Refresh, import, scan

**Stage**:
One of a Sync's two halves: the Channels (live Categories and Listings), then the Titles (movie and show Categories and Listings, and Sports replays). A first Sync lands each Stage as soon as it is ready, so Live and Home open on the Channels while the Titles are still read.
_Avoid_: Phase, half, pass

**Step**:
What a Sync is doing now, as the first-Sync checklist names it: Channels, Movies, Shows, Saving. Matching after a Sync isn't a Step.
_Avoid_: Stage, phase

**Pending catalog**:
A later Sync's finished catalog, staged beside the one on screen until the app lands it between screens (coming to the front, or entering a top-level screen), never while the player is open, so rows never move under focus. A Sync started meanwhile discards it.
_Avoid_: Staged, queued, held catalog

**Clutter**:
Listings that aren't watchable content: separator rows, finished Event channels and empty Event slots ("NO EVENT STREAMING", "No event"), radio, music videos and audiobooks.
_Avoid_: Junk

### Titles and versions

**Title**:
A film or show as one work, identified by its Title number, regardless of how many Listings the Provider has for it.
_Avoid_: Content, media, item, movie (when a show is also meant)

**Title number**:
The number the Provider files a Listing under in its catalog, the same for every Listing of one work in every language (the Provider copies TMDB's numbering; movies and shows are separate number spaces). It is read from the Provider and never looked up anywhere: a Listing without one is Unmatched. It is also what subtitle services are asked by.
_Avoid_: TMDB ID, external ID

**Title facts**:
What Wikidata (CC0) knows of a Title by its Title number, through the cloud: its original language, its IMDb ID and its English and French Wikipedia article names, or that Wikidata has no item for it. The device asks after a Sync, in batches, for every Title it has no answer for, keeps the answers with its catalog and lands them between screens like a Pending catalog; a Title the cloud couldn't answer for is asked again. Without a cloud nothing is known.
_Avoid_: Wikidata metadata, TMDB metadata (which is being retired)

**Movie**:
A Title that is a single film.
_Avoid_: Film, VOD

**Show**:
A Title made of seasons of Episodes.
_Avoid_: Series, TV show

**Episode**:
One episode of a Show, identified by season and episode number. A Show's next Episode is the one the Viewer stopped in, else the one after the one they last Watched, else the first: its details' Play plays it ("Continue S1 E6"), and the Episode picker opens on it. An Episode names its languages only where they differ from the Show's usual set, the languages most of its Episodes have. The picker lists the Episodes the Provider has, and no others.

**Credits**:
The end of a Movie's or Episode's file: its last 10%, or its last 10 minutes when that is less (ADR 0008). Providers mark none, so this rule stands in: from here a Movie is Watched, and an Episode offers the next one.
_Avoid_: Outro, end card

**Version**:
One playable copy of a Movie or Episode, coming from one Listing (for an Episode, from the show Listing whose details list it), with its own language, quality and Source.
_Avoid_: Duplicate, copy, variant, stream

**Backup**:
A Version equivalent to another (same language and quality), tried automatically when the preferred one fails to start. The Viewer can also choose one, as equivalent copies can still differ on screen (burned-in subtitles).
_Avoid_: Mirror, fallback

**Match**:
The link between a Listing and its Title.
_Avoid_: Merge, dedupe

**Mismatch**:
A Listing whose Title number points to the wrong Title (for example `FR - Trotro` carrying Game of Thrones' number). It is told by its year: a Listing dated more than a year away from its sibling Listings' year is a Mismatch, and is kept apart as an Unmatched Title of its own.

**Unmatched Title**:
A Title of one Listing alone: the Listing has no Title number, or is a Mismatch. It is named and pictured by its Listing, and never grouped with another.
_Avoid_: Orphan, suspect, candidate

**Source**:
The streaming service a Version was copied from, according to its Prefix (`NF` is Netflix, `D+` is Disney+).
_Avoid_: Origin, platform

**Untagged Version**:
A Version whose Prefix names a Source but no language. A streaming service's or Blu-ray's copy keeps the original audio, so it is Likely in the Title's original language when Wikidata names one.

**Audio-description Version**:
A Version whose main audio narrates the picture for blind and low-vision viewers; never picked automatically.

**Original audio**:
A Version attribute: its audio is in the Title's original language (Wikidata's, when it names one; otherwise only a Header read or Playback can tell). Confirmed by a Header read or Playback, or Likely for an Untagged Version. A Version whose Prefix names another language can still have it: `NL - The Shadow's Edge` carries Mandarin with Dutch, German and English subtitles.
_Avoid_: Undubbed, original-language Version, OV

**Sports replay**:
A Listing of a past game filed in the Provider's VOD catalog (Prefix `SOC`, or a sports Category), named after its two teams and dated. It is not a Title: its Title number, when it has one, is wrong. The hub shows the Replays of the Viewer's Leagues; a Game that is over shows its own. It plays as its Listing's Version alone, kept apart from the Titles as an Unmatched Movie's would be, so where the Viewer stopped is remembered.
_Avoid_: Highlight (a highlights show is not a Replay)

### Tracks and quality

**Track**:
An audio or subtitle stream inside a Version's file, with a language. A Channel's closed captions (CEA-608) are a subtitle Track with none.
_Avoid_: Stream

**Confirmed**:
A Version attribute learned from the file itself, through a Header read, Playback, or the Provider's Episode details (which name the resolution and the first audio Track's language).

**Likely**:
A Version attribute inferred from its Prefix, Category or the Title's original language, not yet Confirmed.
_Avoid_: Guessed, estimated

**Header read**:
Reading the start of a Version's file to learn its Tracks, resolution and bitrate without playing it.
_Avoid_: Probe, scan

**Background reading**:
Header reads done unprompted, one at a time, while nothing is playing.
_Avoid_: Crawl, prefetch

**Best playable Version**:
The highest-quality Version in the Viewer's Content languages that the Viewer's device and TV can display, within the Quality cap.
_Avoid_: Default version, preferred version

**Quality cap**:
The highest quality the Viewer allows the app to pick.
_Avoid_: Max quality, downgrade

**Lower Version**:
What the player suggests after repeated buffering: a Version in the playing one's language with a lower quality, or one whose quality isn't known. The playing Version's quality is what the player found in its file, so a Movie whose Prefix says nothing still gets one. Never switched to by itself.
_Avoid_: Downgrade, fallback

**Player engine**:
What decodes and shows a Version: Media3 by default, or VLC for the rare file Media3 can't open. The Viewer can switch with "Try other player".
_Avoid_: Backend, decoder

### Viewer, languages and region

**Viewer**:
The person using the app.
_Avoid_: User, customer

**Content language**:
A language whose Versions the Viewer wants to see when browsing; Versions in other languages stay in search and in a Title's Versions list.
_Avoid_: Filter language, audio preference

**Prefer original audio**:
The Viewer's setting that a Title plays with Original audio when any Version has it, ahead of a Version in a Content language at any quality; quality decides among those. When the audio isn't in a Content language, subtitles in the first Content language come on, from the file when it has them, else downloaded.
_Avoid_: Original version mode, subtitles mode

**App language**:
The language of the app's own interface, and the language whose Listing names and describes a Title when the Provider has one in it (else the English Listing's, else any).
_Avoid_: UI language, locale

**Region**:
The country that sets the default leagues. Canada when the device doesn't say.
_Avoid_: Country, locale, market

### Household

**Account**:
An email address a household signs its devices in with, through a Magic link and never a password. It owns each Profile's history and settings and, later, the Purchase; it never holds the Provider login.
_Avoid_: User, login, user account

**Sign-in code**:
The short code and QR a TV shows so a phone can sign it in to an Account.
_Avoid_: Pairing code, device code

**Magic link**:
The link emailed to an Account's address that, tapped once, signs a TV in, resets the Household PIN or deletes the Account.
_Avoid_: Login link, OTP

**Profile**:
One person's place in a household: their Continue watching, Favourites, Leagues, Content languages and App language, apart from the others'. A TV stays on the last Profile used; nobody picks one on launch.
_Avoid_: User, persona, viewer slot

**Kids profile**:
A Profile that sees only the Kids catalog, with no sports hub, Channel browser, Trailer row or Versions screen, and that can't be left without the Household PIN.
_Avoid_: Kids mode, child lock, parental mode

**Kids catalog**:
The Titles any of whose Listings sits in a Category named with a Kids word (KIDS, ENFANTS, KINDER, INFANTILES…), and the Channels of those Categories.
_Avoid_: Children's section, safe content

**Household PIN**:
The four digits that let a Viewer leave a Kids profile, the app's only lock.
_Avoid_: Parental code, password

### Live and sports

**Channel**:
A live broadcast as one thing, however many Listings the Provider has for it: `VIP: TNT SPORT 1 ᴴᴰ` and `GOLD: TNT SPORT 1 SD` are one Channel. It is named without its Tier tag or quality words; its country tag tells it from a namesake elsewhere, so `UK: CNN` and `US: CNN` are two Channels.
_Avoid_: Stream, station

**Channel variant**:
One Listing of a Channel: a copy at one quality (4K, HD, SD, RAW), Likely from its name. Watching a Channel starts its best variant within the Quality cap, and the others stand in when it fails, as Versions and Backups do for a Movie. Two Listings under the identical name are variants of each other.
_Avoid_: Duplicate channel

**Tier tag**:
The package word at the start of a live Listing's name (`VIP:`, `GOLD:`, `4K:`, `8K:`, `PRIME:`) that says which Provider package it comes from. It is not part of the Channel. A *candidate Tier tag* is a word a Sync found there whose removal would make a Listing the same Channel as another; it is reported for the curator and is a Tier tag only once a hand line names it.
_Avoid_: Prefix (which is the Movie and Show word)

**Event channel**:
A live Listing that carries one scheduled Game and is named after it, such as `Football: Wolfsburg vs Köln @ Oct 24 11:55`. Its name, not the TV guide, is how a Game finds it: the Provider's guide covers none of them.
_Avoid_: PPV channel, game channel

**Event Category**:
A Category whose Listings are mostly Event channels and empty Event slots, such as `US| ESPN+ PPV`. Its live Games show in the hub; the Category itself is not browsed.
_Avoid_: PPV category

**Game**:
A scheduled sports contest from schedule data, including races and fights. The hub shows it once, never with a score, and its page lists its Feeds.
_Avoid_: Event, match, fixture

**Feed**:
One way of watching a Game: a Channel or an Event channel carrying it, with a language and, where known, a Broadcaster. The best Feed is in a Content language, dedicated to the Game (an Event channel) rather than a network that may show another game, then of the best quality; the League network is a Feed only when the Game has no other left. The Viewer can mark a Feed "Not this game" to drop it for that Game in their Profile: it stays listed, last and marked, and is never started by Play. On the Game page a Feed is named by what tells it apart, its service then its broadcast ("ESPN+ · Sabres broadcast"); the player names it as the Provider does.
_Avoid_: Stream, source, link

**Broadcaster**:
The network the schedule says carries a Game, such as Fox, ESPN+ or Apple TV. Its Channel is one of the Game's Feeds only when the schedule names it for that Game, and only from the United States, the Region or a Content language's country: `TR: FOX` is a namesake.
_Avoid_: Network, provider

**League network**:
A League's own Channel, such as NHL Network, NBA TV, NFL Network or MLB Network. It is a Game's Feed of last resort: offered only when the Game has no Event channel and no Broadcaster Feed left, since it shows one game of the night, and ranked after both.
_Avoid_: League channel, fallback feed

**Favourite**:
A Channel the Viewer has saved. It stays saved whichever variants the Provider lists for the Channel from one Sync to the next.
_Avoid_: Bookmark

**League**:
A competition whose Games the sports hub follows, such as the NHL or the Premier League. Each has a sport (hockey, football, basketball, baseball, soccer, motorsport, golf, fighting, rugby, Australian football), which Settings groups it under. The Region picks the default Leagues (Canada: NHL, NFL, NBA, MLB, CFL, MLS, Premier League); the Viewer turns Leagues on and off in Settings. Only Leagues ESPN's scoreboard serves and the Provider carries are offered: besides the first fifteen team Leagues, the Europa League, EFL Championship, Liga Portugal, 2. Bundesliga, Women's Super League, NRL and Top 14 (rugby) and AFL (Australian football), on in no Region until the Viewer turns them on. F1, NASCAR Cup, IndyCar, the PGA Tour and the LPGA are event Leagues: their Games have no teams. F1's are a Grand Prix's Qualifying, Sprint and Race, named after the Grand Prix; NASCAR Cup's are its races, named after the track ("Martinsville") or the race ("Daytona 500"); IndyCar's are its races, named as ESPN names them ("Grand Prix of Mid-Ohio"); the PGA Tour's and the LPGA's are a tournament's rounds (Round 1 to the Final round, one a day), named after the tournament. An event League is on in no Region until the Viewer turns it on. UFC is a fight-card League: each fight card is one Game, prelims and main card together, named after its main event, with its two fighters where a Game's teams go; it too is on in no Region until the Viewer turns it on.
_Avoid_: Competition, sport

**Pinned team**:
A team whose Games are shown first.
_Avoid_: Favourite team, followed team

### Discovery

**Trending**:
The Titles among the Provider's that Wikipedia's readers looked up most over the last seven days, in the App language's Wikipedia and English, filtered to the Viewer's Content languages. Unranked: no numerals, no rank shown anywhere.
_Avoid_: Chart, top list, Top 10

**Row**:
A row of cards on Home, Movies, Shows or Live: Continue watching, Trending, New on your service, Favourites or a genre; on Live, Live now, Today, a League, More live events, Replays, Favourites and the Channels' countries. A Title, or a Game, shows in one row of a screen only, the highest it fits; the rows below skip what a row above shows. Continue watching and Favourites stand apart.
_Avoid_: Shelf, carousel, category

**Continue watching**:
The Movies the Viewer stopped partway through and hasn't Watched, and the Shows they played an Episode of until they Watched its last, last played first. A Show shows the Episode to watch next: the one they stopped in, else the one after the last they Watched ("Start next episode"). A Movie's Title page offers "Continue from 1:02:13", "Restart" and "Remove from Continue watching", which forgets where they stopped.
_Avoid_: Resume list, in progress

**Watched**:
A Movie or Episode the Viewer played to the end, or stopped in its last 10% or its last 10 minutes, whichever is shorter (about where the credits start; ADR 0008). Play starts a Watched one from the beginning, and any other from where the Viewer stopped.
_Avoid_: Finished, completed, seen

**New on your service**:
Titles whose Listings the Provider added recently, newest first, ties by the Provider's rating.
_Avoid_: Recently added, new releases

### Paying

**Purchase**:
The one-time payment a household makes to Unlock Zugig on its devices.
_Avoid_: Licence, subscription, premium, pro, upgrade

**Unlocked**:
A device on which the paid features work, because of a Purchase or during its Trial.
_Avoid_: Paid, premium, activated, pro

**Locked**:
A paid feature on a device that isn't Unlocked: it shows what it offers, and OK opens the Unlock screen instead of acting. A disabled control, by contrast, shows why nothing can happen and OK does nothing.
_Avoid_: Paywalled, gated, premium, blocked

**Trial**:
The days after a device first opens Zugig during which it is Unlocked without a Purchase. One per device: reinstalling doesn't start another.
_Avoid_: Free period, demo

**Unlock screen**:
Where a device becomes Unlocked: it names the Purchase and what it includes, and shows the code a phone uses to pay or to add this device to a household's Purchase.
_Avoid_: Paywall, checkout, store
