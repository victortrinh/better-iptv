package dev.betteriptv.core

import java.time.Duration
import java.time.Instant

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
 * A Title's card. Until cloud metadata arrives, [name] and [year] come from the [best] Version's
 * Listing name, and [image] is the Provider's.
 */
data class TitleSummary(val id: TitleId, val name: String, val year: Int?, val image: String?, val best: VersionAttributes)

/** A Title with its Versions grouped by language: [languages] are the Viewer's, [otherLanguages] the rest. */
data class TitleDetails(
    val id: TitleId,
    val name: String,
    val year: Int?,
    val image: String?,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
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
 * One playable copy of a Movie, from one Listing. [backups] are the equivalent Versions (same
 * language and quality), best first, tried when this one fails to start.
 */
data class Version(
    val listingId: Long,
    val name: String,
    val attributes: VersionAttributes,
    val backups: List<Version> = emptyList(),
)

/** How the app knows a Version attribute. */
enum class Provenance {
    /** Inferred from the Prefix, the Category or the Title's original language. */
    Likely,

    /** Read from the file, through a Header read or Playback. */
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
 * A finished Sync. [movieListingCount] counts the movie list, which leaves out Clutter and Sports
 * replays; [unknownPrefixes] are the Prefixes to add to the Prefix table, most common first.
 */
data class CompletedSync(
    val finishedAt: Instant,
    val duration: Duration,
    val movieListingCount: Int,
    val unknownPrefixes: List<UnknownPrefix>,
)

sealed interface SyncStatus {
    /** The last Sync that finished, whose catalog the app shows. */
    val last: CompletedSync?

    data object NeverSynced : SyncStatus {
        override val last: CompletedSync? = null
    }

    data class Running(override val last: CompletedSync?) : SyncStatus

    data class Synced(override val last: CompletedSync) : SyncStatus

    /** The Sync failed; the catalog from [last] is still shown. */
    data class Failed(val problem: SyncProblem, override val last: CompletedSync?) : SyncStatus
}
