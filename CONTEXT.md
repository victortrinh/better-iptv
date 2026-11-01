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
A Provider's mapping from Prefixes and Category hints to Version attributes.
_Avoid_: Dictionary, naming rules

**Sync**:
Refreshing the device's copy of the Provider's catalog.
_Avoid_: Refresh, import, scan

**Clutter**:
Listings that aren't watchable content: separator rows, finished Event channels and empty Event slots ("NO EVENT STREAMING", "No event"), radio, music videos and audiobooks.
_Avoid_: Junk

### Titles and versions

**Title**:
A film or show as one work, identified by its TMDB ID, regardless of how many Listings the Provider has for it.
_Avoid_: Content, media, item, movie (when a show is also meant)

**Movie**:
A Title that is a single film.
_Avoid_: Film, VOD

**Show**:
A Title made of seasons of Episodes.
_Avoid_: Series, TV show

**Episode**:
One episode of a Show, identified by season and episode number. A Show's next Episode is the one the Viewer stopped in, else the one after the one they last Watched, else the first: its details' Play plays it ("Continue S1 E6"), and Down from Play lands on it. An Episode TMDB lists as airing later that no Version has yet is shown with its air date and can't be played.

**Credits**:
The end of a Movie's or Episode's file: its last 10%, or its last 10 minutes when that is less (ADR 0008). Providers mark none, so this rule stands in: from here a Movie is Watched, and an Episode offers the next one.
_Avoid_: Outro, end card

**Version**:
One playable copy of a Movie or Episode, coming from one Listing (for an Episode, from the show Listing whose details list it), with its own language, quality and Source.
_Avoid_: Duplicate, copy, variant, stream

**Backup**:
A Version equivalent to another (same language and quality), tried automatically when the preferred one fails to start.
_Avoid_: Mirror, fallback

**Match**:
The link between a Listing and its Title.
_Avoid_: Merge, dedupe

**Mismatch**:
A Listing whose Provider-supplied TMDB ID points to the wrong Title (for example `FR - Trotro` carrying Game of Thrones' ID).

**Suspect**:
A Listing whose Match is checked by name: it has no TMDB ID, or no sibling Listing with the same ID shares its name.
_Avoid_: Candidate, orphan

**Source**:
The streaming service a Version was copied from, according to its Prefix (`NF` is Netflix, `D+` is Disney+).
_Avoid_: Origin, platform

**Untagged Version**:
A Version whose Prefix names a Source but no language.

**Audio-description Version**:
A Version whose main audio narrates the picture for blind and low-vision viewers; never picked automatically.

**Sports replay**:
A Listing of a past game filed in the Provider's VOD catalog (Prefix `SOC`, or a sports Category), named after its two teams and dated. It is not a Title: its TMDB ID, when it has one, is wrong. The hub shows the Replays of the Viewer's Leagues; a Game that is over shows its own.
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

**App language**:
The language of the app's own interface and of Title metadata (titles, descriptions, posters).
_Avoid_: UI language, locale

**Region**:
The country that sets the Top 10 Chart and the default leagues. Canada when the device doesn't say.
_Avoid_: Country, locale, market

### Live and sports

**Channel**:
A live broadcast as one thing, however many Listings the Provider has for it: `VIP: TNT SPORT 1 ᴴᴰ` and `GOLD: TNT SPORT 1 SD` are one Channel. It is named without its Tier tag or quality words; its country tag tells it from a namesake elsewhere, so `UK: CNN` and `US: CNN` are two Channels.
_Avoid_: Stream, station

**Channel variant**:
One Listing of a Channel: a copy at one quality (4K, HD, SD, RAW), Likely from its name. Watching a Channel starts its best variant within the Quality cap, and the others stand in when it fails, as Versions and Backups do for a Movie. Two Listings under the identical name are variants of each other.
_Avoid_: Duplicate channel

**Tier tag**:
The package word at the start of a live Listing's name (`VIP:`, `GOLD:`, `4K:`, `8K:`) that says which Provider package it comes from. It is not part of the Channel.
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
One way of watching a Game: a Channel or an Event channel carrying it, with a language and, where known, a Broadcaster. The best Feed is in a Content language, dedicated to the Game (an Event channel) rather than a network that may show another game, then of the best quality. The Viewer can mark a Feed "Not this game" to drop it for that Game.
_Avoid_: Stream, source, link

**Broadcaster**:
The network the schedule says carries a Game, such as Fox, ESPN+ or Apple TV. Its Channel is one of the Game's Feeds only when the schedule names it for that Game.
_Avoid_: Network, provider

**Favourite**:
A Channel the Viewer has saved. It stays saved whichever variants the Provider lists for the Channel from one Sync to the next.
_Avoid_: Bookmark

**League**:
A competition whose Games the sports hub follows, such as the NHL or the Premier League. The Region picks the default Leagues (Canada: NHL, NFL, NBA, MLB, CFL, MLS, Premier League); the Viewer turns Leagues on and off in Settings.
_Avoid_: Competition, sport

**Pinned team**:
A team whose Games are shown first.
_Avoid_: Favourite team, followed team

### Discovery

**Chart**:
An external ranked list of Titles (Netflix Top 10 for the Region, TMDB trending this week), filtered to Titles the Provider has in the Viewer's Content languages. The Top 10's Titles carry their rank, counted among the Titles left, so a missing Title leaves no gap; trending's carry none.
_Avoid_: Trending list, top list

**Row**:
A row of cards on Home, Movies, Shows or Live: Continue watching, a Chart, New on your service, Favourites or a genre; on Live, Live now, Today, a League, More live events, Replays, Favourites and the Channels' countries. A Title, or a Game, shows in one row of a screen only, the highest it fits; a Chart keeps every Title it ranks, and the rows below it skip what it shows. Continue watching and Favourites stand apart.
_Avoid_: Shelf, carousel, category

**Continue watching**:
The Movies the Viewer stopped partway through and hasn't Watched, and the Shows they played an Episode of until they Watched its last, last played first. A Show shows the Episode to watch next: the one they stopped in, else the one after the last they Watched ("Start next episode"). A Movie's Title page offers "Continue from 1:02:13", "Restart" and "Remove from Continue watching", which forgets where they stopped.
_Avoid_: Resume list, in progress

**Watched**:
A Movie or Episode the Viewer played to the end, or stopped in its last 10% or its last 10 minutes, whichever is shorter (about where the credits start; ADR 0008). Play starts a Watched one from the beginning, and any other from where the Viewer stopped.
_Avoid_: Finished, completed, seen

**New on your service**:
Titles whose Listings the Provider added recently, ordered by popularity.
_Avoid_: Recently added, new releases
