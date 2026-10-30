package dev.betteriptv.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One movie Listing, as the Provider names and files it, with what its Prefix and Category say about it. */
data class MovieListing(val id: Long, val name: String, val category: String, val attributes: VersionAttributes)

enum class TitleKind { Movie, Show }

/**
 * Which Title a card or details page is about. Movie and show TMDB IDs are separate number spaces,
 * so a Title is keyed by kind and TMDB ID.
 */
sealed interface TitleId {
    val kind: TitleKind

    /** A Title Matched by the TMDB ID its Listings carry. */
    data class Tmdb(override val kind: TitleKind, val tmdbId: Long) : TitleId

    /** A Listing with no usable TMDB ID, standing alone until it can be looked up by name. */
    data class Unmatched(override val kind: TitleKind, val listingId: Long) : TitleId
}

/**
 * A Title's card. [name], [year] and the [image] (a poster URL) come from TMDB in the App language,
 * falling back to English. Without TMDB metadata (an Unmatched Title, or the cloud out of reach),
 * they come from the [best] Version's Listing name and the Provider's image. Home's cards also carry
 * [about], what the info area under a focused card shows.
 */
data class TitleSummary(val id: TitleId, val name: String, val year: Int?, val image: String?, val best: VersionAttributes, val about: TitleAbout? = null)

/** TMDB's description, genres and runtime of a Title, in the App language, falling back to English. */
data class TitleAbout(val description: String?, val genres: List<String>, val runtimeMinutes: Int?)

/** A row on the home screen. */
sealed interface HomeRow {
    /** The Titles the Viewer started and didn't finish, last played first. */
    data class ContinueWatching(val titles: List<InProgress>) : HomeRow

    /** The Channels the Viewer saved, in the order they were added. */
    data class Favourites(val channels: List<Channel>) : HomeRow

    /** Titles with recently added Listings, most popular on TMDB first. */
    data class NewOnYourService(val titles: List<TitleSummary>) : HomeRow

    /** Titles in one of TMDB's genres ([name], in the App language), most popular first. */
    data class Genre(val name: String, val titles: List<TitleSummary>) : HomeRow
}

/**
 * A Title the Viewer stopped in: its card, a landscape [still] URL from TMDB, how far they got
 * (0 to 1) and how much is [left], both null when the player didn't know the file's length.
 */
data class InProgress(val title: TitleSummary, val still: String?, val progress: Float?, val left: Duration? = null)

/**
 * A Title with its Versions grouped by language: [languages] are the Viewer's, [otherLanguages] the
 * rest. [name], [year] and [image] are as on its card. The rest is TMDB metadata in the App language,
 * falling back to English, and empty without it ([fromTmdb] false).
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
    /** A landscape still URL, to fill the screen behind the details. */
    val backdrop: String? = null,
    /** The Title's logo art URL, drawn in place of its name; null when TMDB has none in the App language or English. */
    val logo: String? = null,
    /** The main cast's names, billing order. */
    val cast: List<String> = emptyList(),
    /** Whether the metadata came from TMDB, whose terms ask for attribution where it is shown. */
    val fromTmdb: Boolean = false,
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
 * so it has every season any Version has, and the ones TMDB lists as not aired yet. [unreadVersions]
 * counts the Versions whose details the Provider couldn't give (and that weren't kept from before):
 * their Episodes are missing. [next] is the Episode to watch next ([nextEpisode]), null when there are none.
 */
data class ShowEpisodes(val seasons: List<Season>, val unreadVersions: Int, val next: EpisodeNumber? = null)

/** One season of a Show, its Episodes in order. Specials (season 0) come after the others. */
data class Season(val number: Int, val episodes: List<Episode>)

/**
 * One Episode of a Show with its Versions grouped by language, as a Title's are: [languages] are
 * the Viewer's, [otherLanguages] the rest, so together they are the languages it's available in.
 * [name] is the Episode's name as the Provider gives it in the best Version (as TMDB gives it, for
 * one not aired yet), null when it gives none.
 *
 * [progress] is how far the Viewer got, from 0 to 1, null when they never played it; it is 1 once
 * it's [watched]. An Episode TMDB lists as airing on [availableOn] has no Version yet, so it can't be played.
 */
data class Episode(
    val season: Int,
    val number: Int,
    val name: String?,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
    val progress: Float? = null,
    val watched: Boolean = false,
    val availableOn: LocalDate? = null,
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
)

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
    val quality: Known<Quality>? = null,
    val hdr: Known<Hdr>? = null,
    /** The streaming service the Version was copied from, such as "Netflix". */
    val source: Known<String>? = null,
    val dolbyAudio: Boolean = false,
    val cam: Boolean = false,
    val audioDescription: Boolean = false,
    /** The file's bitrate in bits per second, from a Header read or the Provider's movie details. */
    val bitrate: Known<Long>? = null,
)

/** A Prefix the Prefix table doesn't know, seen on [listingCount] Listings such as [example]. */
data class UnknownPrefix(val prefix: String, val listingCount: Int, val example: String)

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

/**
 * A finished Sync. [movieListingCount] and [showListingCount] count the movie and show lists, which
 * leave out Clutter and Sports replays; [unknownPrefixes] are the Prefixes to add to the Prefix
 * table, most common first.
 * [rematchedListings] counts the Listings Matched by name instead of by their Provider ID:
 * Mismatches, and Listings without an ID that TMDB found. It grows after the Sync finishes, as
 * the cloud answers, and the Titles change with it.
 */
data class CompletedSync(
    val finishedAt: Instant,
    val duration: Duration,
    val movieListingCount: Int,
    val unknownPrefixes: List<UnknownPrefix>,
    val rematchedListings: Int = 0,
    val showListingCount: Int = 0,
)

sealed interface SyncStatus {
    /** The last Sync that finished, whose catalog the app shows. */
    val last: CompletedSync?

    data object NeverSynced : SyncStatus {
        override val last: CompletedSync? = null
    }

    /**
     * A Sync is reading the Provider's catalog: [moviesRead] movie and [showsRead] show Listings so
     * far, Clutter and Sports replays left out. The Provider sends movies first, and never a total.
     */
    data class Running(override val last: CompletedSync?, val moviesRead: Int = 0, val showsRead: Int = 0) : SyncStatus {
        /** How far through, judged by [last]'s Listing counts; below 1 until the Sync finishes, null without them. */
        val fraction: Float?
            get() {
                val expected = (last ?: return null).let { it.movieListingCount + it.showListingCount }
                if (expected == 0) return null
                return ((moviesRead + showsRead).toFloat() / expected).coerceAtMost(MAX_RUNNING_FRACTION)
            }

        private companion object {
            const val MAX_RUNNING_FRACTION = 0.99f
        }
    }

    data class Synced(override val last: CompletedSync) : SyncStatus

    /**
     * The Sync finished and its catalog shows, and the cloud is being asked about [of] names to Match
     * suspects, [asked] so far. Their Titles regroup once every name is asked. [of] is null while the
     * suspects are still being found, which can take half a minute on a full catalog.
     */
    data class Matching(override val last: CompletedSync, val asked: Int, val of: Int?) : SyncStatus

    /** The Sync failed; the catalog from [last] is still shown. */
    data class Failed(val problem: SyncProblem, override val last: CompletedSync?) : SyncStatus
}
