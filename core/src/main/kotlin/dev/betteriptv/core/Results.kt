package dev.betteriptv.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One movie Listing, as the Provider names and files it, with what its Prefix and Category say about it. */
data class MovieListing(val id: Long, val name: String, val category: String, val attributes: VersionAttributes)

enum class TitleKind { Movie, Show }

/**
 * Which Title a card or details page is about. Movie and show Title numbers are separate number
 * spaces, so a Title is keyed by kind and number.
 */
sealed interface TitleId {
    val kind: TitleKind

    /**
     * A Title grouped by the Title number its Listings carry: the number the Provider files each
     * Listing under (Xtream's `tmdb` field, TMDB's numbering, which the Provider copies), read from
     * the catalog and never looked up (ADR 0014).
     */
    data class Numbered(override val kind: TitleKind, val titleNumber: Long) : TitleId

    /** A Listing with no usable Title number, or a Mismatch: a Title of its own, never grouped with another. */
    data class Unmatched(override val kind: TitleKind, val listingId: Long) : TitleId
}

/**
 * A Title's card. [name] and [year] come from its Listing in the App language, else the English
 * one, else the [best] Version's. Its [posters] are the Provider's poster URLs in the order the card
 * tries them (ADR 0014, #526): the App language Listing's, else a Content language Listing's in the
 * Viewer's order, else the English one's, else the file most Listings share; one under 300 px wide
 * ([Library.MIN_POSTER_WIDTH]) is left out where its URL names the width, and passed over for the
 * next where it decodes that narrow. The [image] is the first, null when none is left, and the card
 * then shows the name on glass. Home's cards also carry [about], what the info area under a focused
 * card shows. No card carries a rank (#477).
 */
data class TitleSummary(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val image: String?,
    val best: VersionAttributes,
    val about: TitleAbout? = null,
    val posters: List<String> = listOfNotNull(image),
)

/**
 * What the info under a focused Home row tells of a Title, from what the device already holds, so
 * focus costs no Provider call (ADR 0014): its [genres], as the Prefix table maps the Provider's
 * Category and genre field, named in the App language, and the Provider's [rating] on a 10 scale
 * (null when it gives none or zero).
 */
data class TitleAbout(
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
)

/** A screen that opens on a featured Title ([Library.featured]): Home, or the Movies or Shows tab. */
enum class FeaturedScreen { Home, Movies, Shows }

/**
 * The Title a screen's hero features: its [name] and [year] as on its card, its [runtimeMinutes],
 * [genres] and [description] (the plot) as the Provider gives them, its [backdrop] (the Provider's,
 * ADR 0014), and the attributes its badges name ([badge], [Library.featuredBadge]): the Version its
 * Play starts, as on its page; null for a Show whose Episodes were never read.
 * Its [posters] are its card's, in the order the card tries them ([TitleSummary.posters]): its Title
 * treatment is cut from the one the card shows (ADR 0016).
 */
data class FeaturedTitle(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val description: String?,
    val backdrop: String,
    val badge: VersionAttributes?,
    val posters: List<String> = emptyList(),
)

/** A row on Home, on the Movies or Shows tab, or on Live (CONTEXT.md: Row). */
sealed interface HomeRow {
    /** The Titles the Viewer started and didn't finish, last played first. */
    data class ContinueWatching(val titles: List<InProgress>) : HomeRow

    /** The Channels the Viewer saved, in the order they were added. */
    data class Favourites(val channels: List<Channel>) : HomeRow

    /** The Channel browser's countries (Live's last row), the Viewer's Region first; OK on one opens its Channels screen. */
    data class Channels(val countries: List<LiveCountry>) : HomeRow

    /**
     * Trending (CONTEXT.md): the Provider's Titles Wikipedia's readers looked up most over the last
     * seven days, most viewed first but unranked, kept to the Titles the Viewer can watch: movies and
     * shows mixed on Home ([kind] null), one kind on its tab.
     */
    data class Trending(val titles: List<TitleSummary>, val kind: TitleKind? = null) : HomeRow

    /**
     * Titles with recently added Listings, newest first: movies and shows on Home
     * ([kind] null), one kind on its tab (New movies, New shows).
     */
    data class NewOnYourService(val titles: List<TitleSummary>, val kind: TitleKind? = null) : HomeRow

    /**
     * On a Kids profile: the newest Titles of the Kids catalog, newest first: movies
     * and shows on Home ([kind] null), one kind on its tab.
     */
    data class NewForKids(val titles: List<TitleSummary>, val kind: TitleKind? = null) : HomeRow

    /** On a Kids profile: the Kids catalog's newest Titles of [kind] left after New for kids (Movies for kids, Shows for kids). */
    data class ForKids(val kind: TitleKind, val titles: List<TitleSummary>) : HomeRow

    /** On Live on a Kids profile: the Channels of [country]'s Kids Categories, in the Provider's order. */
    data class KidsChannels(val country: LiveCountry, val channels: List<Channel>) : HomeRow

    /** Titles in one genre ([name], in the App language), from the Provider's genre fields and Category names. */
    data class Genre(val name: String, val titles: List<TitleSummary>) : HomeRow

    /** On Live, and on Home after Continue watching: the Games in progress that have a Feed, by start. A live Game with no Feed is left out. */
    data class LiveNow(val games: List<GameFeeds>) : HomeRow

    /** On Live: the Games still to start today (the device's day), by start, those with no Feed shown disabled. */
    data class Today(val games: List<GameFeeds>) : HomeRow

    /** On Live: [league]'s Games of the week ahead not shown in a row above, by start. A League with none gets no row. */
    data class LeagueGames(val league: League, val games: List<GameFeeds>) : HomeRow

    /**
     * On Live, after the League rows: the Event channels on now or soon that no Game matched
     * (tennis, snooker, handball, college games no schedule knows), by start. Left out when empty.
     */
    data class MoreLiveEvents(val events: List<UnmatchedEventChannel>) : HomeRow

    /** On Live, after the League rows: the Sports replays of the Leagues on (CONTEXT.md: Sports replay), newest first. Left out when there is none. */
    data class Replays(val replays: List<LeagueReplay>) : HomeRow
}

/**
 * An Event channel no Game matched, on the More live events row: the merged [channel], watched as
 * any Channel is (its best variant first), and when its name says its event [start]s, as
 * [eventStart] reads it, for the time under the rail.
 */
data class UnmatchedEventChannel(val channel: Channel, val start: Instant)

/**
 * A Title the Viewer stopped in: its card, a [still] URL (its backdrop, else its poster), how far they got
 * (0 to 1) and how much is [left], both null when the player didn't know the file's length. For a
 * Show, those are about its [episode], the one to watch next.
 */
data class InProgress(val title: TitleSummary, val still: String?, val progress: Float?, val left: Duration? = null, val episode: NextEpisode? = null)

/** The Episode of a Show the Viewer is at: the one they stopped in ([started]), else the one after the last they Watched. Its [name] is null when the Provider gives none. */
data class NextEpisode(val number: EpisodeNumber, val name: String?, val started: Boolean)

/**
 * A Title with its Versions grouped by language: [languages] are the Viewer's, [otherLanguages] the
 * rest. [name], [year] and [image] are as on its card. What the Title is comes from the Provider
 * (ADR 0014): its [description] is the plot, its [genres] the Prefix table's mapping of its Category
 * and genre field named in the App language, its [runtimeMinutes], [cast], [directors], [rating]
 * (on a 10 scale, null when absent or zero) and [trailer] what its movie details or show Listing
 * say; each empty when the Provider doesn't say. A Movie's details are read when its page opens and
 * kept for good, so a page opens whole from kept data when the Provider is unreachable. Its
 * [backdrop] is the Provider's too, null when it has none (the page then shows the poster).
 */
data class TitleDetails(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val image: String?,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    /** The Provider's backdrop URL, to fill the screen behind the details: a Movie's from its details, a Show's from its Listing; null when there is none or it was refused. */
    val backdrop: String? = null,
    /** The main cast's names, as the Provider lists them. */
    val cast: List<String> = emptyList(),
    /** The YouTube video ID of its trailer, as the Provider gives it (ADR 0012); null when it gives none, and the Trailer action stays out. */
    val trailer: String? = null,
    /** The director's name, or the directors', as the Provider lists them. */
    val directors: List<String> = emptyList(),
    /** The Provider's rating on a 10 scale ("★ 6.6"); null when it gives none or zero. */
    val rating: Double? = null,
    /**
     * The language the Title was released in (Wikidata's, a primary subtag, #476), when Prefer original
     * audio acts on it: the setting is on, Wikidata names the language and it is no Content language. Its
     * group then sits among [languages]. Null otherwise.
     */
    val originalLanguage: String? = null,
    /** Its posters in the order its page tries them, as a card's ([TitleSummary.posters]); [image] is the first. */
    val posters: List<String> = listOfNotNull(image),
)

/**
 * A Title's Versions in one language, best first. [language] is a primary language subtag (`fr`
 * also holds `fr-CA` Versions), or null for Untagged Versions.
 */
data class LanguageGroup(val language: String?, val versions: List<Version>) {
    /** Every Version in the group, Backups included. */
    val versionCount: Int get() = versions.sumOf { 1 + it.backups.size }
}

/**
 * A Show's Episodes, merged by season and episode number across all its Versions' show details,
 * so it has every season any Version has, and no Episode the Provider lacks. [unreadVersions]
 * counts the Versions whose details the Provider couldn't give (and that weren't kept from before):
 * their Episodes are missing. [next] is the Episode to watch next ([nextEpisode]), null when there are none.
 *
 * [usualLanguages] is the Show's usual language set: the named languages (primary subtags) most of
 * its Episodes are available in ([usualLanguages]). An Episode names its languages only when they
 * differ from it ([languagesNamed]).
 */
data class ShowEpisodes(
    val seasons: List<Season>,
    val unreadVersions: Int,
    val next: EpisodeNumber? = null,
    val usualLanguages: Set<String> = usualLanguages(seasons),
) {
    /**
     * The languages [episode] is available in, the Viewer's first, when they differ from the Show's
     * [usualLanguages]; empty when they are the usual set, or when it has no Version yet. A Version
     * whose language isn't named is never shown as one.
     */
    fun languagesNamed(episode: Episode): List<String> {
        val named = episode.namedLanguages()
        return if (named.toSet() == usualLanguages) emptyList() else named
    }
}

/** [Episode]'s named languages, the Viewer's first, each once. */
private fun Episode.namedLanguages(): List<String> = (languages + otherLanguages).mapNotNull { it.language }.distinct()

/**
 * The named languages more than half of [seasons]' Episodes that have a Version are available in:
 * what an Episode of the Show usually offers, so an Episode that offers just that needn't say so.
 */
fun usualLanguages(seasons: List<Season>): Set<String> {
    val offered = seasons.flatMap { it.episodes }.map { it.namedLanguages() }
    if (offered.isEmpty()) return emptySet()
    return offered.flatten().groupingBy { it }.eachCount().filterValues { it * 2 > offered.size }.keys
}

/** One season of a Show, its Episodes in order. Specials (season 0) come after the others. */
data class Season(val number: Int, val episodes: List<Episode>)

/**
 * One Episode of a Show with its Versions grouped by language, as a Title's are: [languages] are
 * the Viewer's, [otherLanguages] the rest, so together they are the languages it's available in.
 * [name] is the Episode's name as the Provider gives it in the best Version, null when it gives
 * none; [still] (a 16:9 image URL) and [runtimeMinutes] come from the Provider's show details of
 * that Version, else of any that has them, null when none does.
 *
 * [progress] is how far the Viewer got, from 0 to 1, null when they never played it; it is 1 once
 * it's [watched].
 */
data class Episode(
    val season: Int,
    val number: Int,
    val name: String?,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
    val progress: Float? = null,
    val watched: Boolean = false,
    val still: String? = null,
    val runtimeMinutes: Int? = null,
) {
    val id: EpisodeNumber get() = EpisodeNumber(season, number)
}

/**
 * One playable copy of a Movie or Episode, from one Listing (for an Episode, [listingId] is the
 * Provider's Episode id). [backups] are the equivalent Versions (same language and quality), best
 * first, tried when this one fails to start.
 *
 * A Channel plays as a Version too, [listingId] being its stream id, with a [liveFormat]: `ts` for
 * MPEG-TS, with the same stream as HLS (`m3u8`) as its Backup.
 */
data class Version(
    val listingId: Long,
    val name: String,
    val attributes: VersionAttributes,
    val backups: List<Version> = emptyList(),
    val liveFormat: String? = null,
) {
    /**
     * This Version as the Viewer gets it by choosing its Backup [backup]: [backup] first, then this
     * one, then its other Backups, so a dead [backup] still falls back to the rest.
     */
    fun ledBy(backup: Version): Version =
        backup.copy(backups = listOf(copy(backups = emptyList())) + backups.filter { it.listingId != backup.listingId })
}

/** How the app knows a Version attribute. */
enum class Provenance {
    /** Inferred from the Prefix, the Category or the Title's original language. */
    Likely,

    /** Read from the file, through a Header read, Playback or the Provider's Episode or movie details. */
    Confirmed,
}

data class Known<out T>(val value: T, val provenance: Provenance)

enum class Quality { SD, HD, FullHD, UHD }

enum class Hdr { HDR10, DolbyVision }

/**
 * What the app knows about one Version. Languages are BCP 47 tags (`en`, `fr-CA`, `ta`); `mul`
 * among the subtitles means "many languages". Null means nothing is known yet.
 */
data class VersionAttributes(
    /** The main audio's language. Null for an Untagged Version. */
    val language: Known<String>? = null,
    val subtitles: Known<Set<String>>? = null,
    /**
     * Every language the audio Tracks are in, Confirmed by a Header read, Playback or the Provider's
     * Episode details (which name the first Track's only). Null until one of those has seen the file.
     */
    val audioLanguages: Known<Set<String>>? = null,
    val quality: Known<Quality>? = null,
    val hdr: Known<Hdr>? = null,
    /** The streaming service the Version was copied from, such as "Netflix". */
    val source: Known<String>? = null,
    val dolbyAudio: Boolean = false,
    val cam: Boolean = false,
    val audioDescription: Boolean = false,
    /** The file's bitrate in bits per second, from a Header read or the Provider's movie details. */
    val bitrate: Known<Long>? = null,
    /**
     * Whether the Prefix is Multi-audio: its Provider's files mostly carry several audio languages
     * (learned, ADR 0014), so it names no language and its Versions are Untagged, as a Source's are.
     */
    val multiAudio: Boolean = false,
) {
    /** Whether an unnamed language is Likely the Title's original: the Prefix names a Source (`NF`, `D+`) or is Multi-audio. */
    internal val untaggedCopy: Boolean get() = source != null || multiAudio
}

/** A Prefix the Prefix table doesn't know, seen on [listingCount] Listings such as [example]. */
data class UnknownPrefix(val prefix: String, val listingCount: Int, val example: String)

/**
 * A candidate Tier tag (#439): a [word] before the colon at the start of live Listings' names whose
 * removal makes [listingCount] of them the same Channel as another live Listing, such as [example].
 * Reported to the cloud for the curator, never applied: it is a Tier tag only once a hand line says so.
 */
data class CandidateTierTag(val word: String, val listingCount: Int, val example: String)

sealed interface LoginResult

data class LoggedIn(val expiresOn: Instant?) : LoginResult

/** Why a Sync failed. */
sealed interface SyncProblem

/** Why the Provider couldn't be used. */
sealed interface ProviderProblem : LoginResult, SyncProblem

data object WrongLogin : ProviderProblem

data class Expired(val expiredOn: Instant?) : ProviderProblem

/** The Provider couldn't be reached or sent something unreadable. [detail] never contains the login. */
data class Unreachable(val detail: String) : ProviderProblem

/** The catalog downloaded but couldn't be stored on the device. */
data class CatalogNotSaved(val detail: String) : SyncProblem

/** What a Sync is doing, as the first-run checklist names it. */
enum class SyncStep { Channels, Movies, Shows, Saving }

/**
 * A finished Sync. [movieListingCount] and [showListingCount] count the movie and show lists, which
 * leave out Clutter and Sports replays, and [channelListingCount] the Channel variants; [unknownPrefixes]
 * are the Prefixes to add to the Prefix table, most common first, and [candidateTierTags] the words
 * that may be Tier tags (most merging first). [duration] runs from the Sync's start
 * to the end of its swap; for a held catalog, the time it took to compute plus the time its swap took.
 * [mismatches] counts the Unmatched Titles the year rule made: Listings dated more than a year from
 * their siblings' year, kept apart from the Title their number names (ADR 0014).
 */
data class CompletedSync(
    val finishedAt: Instant,
    val duration: Duration,
    val movieListingCount: Int,
    val unknownPrefixes: List<UnknownPrefix>,
    val mismatches: Int = 0,
    val showListingCount: Int = 0,
    val channelListingCount: Int = 0,
    val candidateTierTags: List<CandidateTierTag> = emptyList(),
)

/**
 * How a Sync is going. A first Sync lands in two stages: its Channels first, so Live and Home open
 * within seconds ([channelsAt] is set while [last] is still null), then its movies and shows. A later
 * Sync computes both behind the catalog on screen and, when held, waits as [Ready] for the app to
 * apply it between screens, so rows never move under focus.
 */
sealed interface SyncStatus {
    /** The last whole Sync that finished, whose catalog the app shows. */
    val last: CompletedSync?

    /** When the Channels on screen landed: before [last] on a first Sync, with it afterwards; null until any have. */
    val channelsAt: Instant?

    data class NeverSynced(override val channelsAt: Instant? = null) : SyncStatus {
        override val last: CompletedSync? = null
    }

    /**
     * A Sync is at [step], having read [entriesRead] Channel variants, movie and show Listings so far,
     * Clutter and Sports replays left out. The Provider never sends a total.
     */
    data class Running(override val last: CompletedSync?, override val channelsAt: Instant?, val step: SyncStep, val entriesRead: Int = 0) : SyncStatus {
        /** How far through, judged by [last]'s counts; below 1 until the Sync finishes, null without them. */
        val fraction: Float?
            get() {
                val expected = (last ?: return null).let { it.movieListingCount + it.showListingCount + it.channelListingCount }
                if (expected == 0) return null
                return (entriesRead.toFloat() / expected).coerceAtMost(MAX_RUNNING_FRACTION)
            }

        private companion object {
            const val MAX_RUNNING_FRACTION = 0.99f
        }
    }

    /** A later Sync finished computing its catalog at [computedAt]; [last]'s shows until the app applies it. */
    data class Ready(override val last: CompletedSync, val computedAt: Instant) : SyncStatus {
        override val channelsAt: Instant get() = last.finishedAt
    }

    data class Synced(override val last: CompletedSync) : SyncStatus {
        override val channelsAt: Instant get() = last.finishedAt
    }

    /** The Sync failed at [step]; the catalog from [last] is still shown, or on a first Sync whatever landed by [channelsAt]. */
    data class Failed(val problem: SyncProblem, override val last: CompletedSync?, override val channelsAt: Instant?, val step: SyncStep) : SyncStatus
}
