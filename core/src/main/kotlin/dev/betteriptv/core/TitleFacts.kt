package dev.betteriptv.core

/**
 * What Wikidata knows of a Title, by its Title number, through the cloud (#476, ADR 0014): the
 * only facts no Provider field holds. Each is null when Wikidata's item lacks it. A Title Wikidata
 * has no item for is kept as "no item" (a null in the store's map), so it isn't asked again; one
 * the cloud couldn't answer for is asked again after the next Sync.
 */
internal data class TitleFacts(
    /** The language the Title was released in, a primary subtag (`ko`): what an Untagged Version is Likely in, and what Prefer original audio acts on. */
    val originalLanguage: String?,
    /** `tt0068646`; the cloud's subtitles search uses it too. */
    val imdbId: String?,
    /** The English Wikipedia article's name, as Wikipedia shows it ("The Godfather"), for Trending (#477). */
    val enwiki: String?,
    val frwiki: String?,
)

/** What the cloud answered for a batch: each found Title's facts, and the Titles Wikidata has no item for. The rest are unavailable. */
internal class CloudTitleFacts(val found: Map<TitleId.Tmdb, TitleFacts>, val notFound: List<TitleId.Tmdb>)
