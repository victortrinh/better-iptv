# Better IPTV

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
Listings that aren't watchable content: separator rows, finished Event channels, radio, music videos and audiobooks.
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
One episode of a Show, identified by season and episode number.

**Credits**:
The end of a Movie's or Episode's file: its last 5%, or its last 8 minutes when that is less. Providers mark none, so this rule stands in: from here a Title counts as watched, and an Episode offers the next one.
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
A Listing of a past game filed in the Provider's VOD catalog (Prefix `SOC`).
_Avoid_: Highlight

### Tracks and quality

**Track**:
An audio or subtitle stream inside a Version's file, with a language.
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
The country that sets the Top 10 Chart and the default leagues.
_Avoid_: Country, locale, market

### Live and sports

**Channel**:
A live Listing that broadcasts continuously.
_Avoid_: Stream, station

**Channel variant**:
One of several Listings of the same Channel at different qualities (HD, SD, 4K, RAW).
_Avoid_: Duplicate channel

**Event channel**:
A live Listing that carries one scheduled Game and is named after it, such as `Football: Wolfsburg vs Köln @ Oct 24 11:55`.
_Avoid_: PPV channel, game channel

**Game**:
A scheduled sports contest from schedule data, including races and fights.
_Avoid_: Event, match, fixture

**Favourite**:
A Channel the Viewer has saved.
_Avoid_: Bookmark

**Pinned team**:
A team whose Games are shown first.
_Avoid_: Favourite team, followed team

### Discovery

**Chart**:
An external ranked list of Titles (Netflix Top 10 for the Region, TMDB trending, Trakt most watched), filtered to Titles the Provider has.
_Avoid_: Trending list, top list

**New on your service**:
Titles whose Listings the Provider added recently, ordered by popularity.
_Avoid_: Recently added, new releases
