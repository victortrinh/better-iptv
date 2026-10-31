package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * The cloud's Charts answer for a Region (cloud/src/charts.ts), as the device keeps it: Netflix's
 * Top 10 movies and shows as TMDB IDs in rank order, the world's when [worldwide], and TMDB's
 * trending this week, and TMDB Discover's genre lists. Parts this app doesn't read yet are kept in
 * the JSON and left alone here.
 */
internal class Charts(json: String) {
    private val tree = JsonReader.of(Buffer().writeUtf8(json)).use { it.readJsonValue() } as? Map<*, *>
    private val top = tree?.get("top") as? Map<*, *>
    private val trending = tree?.get("trending") as? Map<*, *>

    val worldwide: Boolean = top?.get("worldwide") == true
    val topMovies: List<TitleId.Tmdb> = titles(top?.get("movies"))
    val topShows: List<TitleId.Tmdb> = titles(top?.get("shows"))

    /** TMDB's trending this week, movies and shows mixed, in its order. */
    val trendingAll: List<TitleId.Tmdb> = titles(trending?.get("all"))

    /** TMDB's trending movies this week, in its order, for the Movies tab. */
    val trendingMovies: List<TitleId.Tmdb> = titles(trending?.get("movies"))

    /** TMDB's trending shows this week, in its order, for the Shows tab. */
    val trendingShows: List<TitleId.Tmdb> = titles(trending?.get("shows"))

    /**
     * Each TMDB genre's most popular movies or shows across TMDB (Discover), in its order, named in
     * the App language, for the Movies and Shows tabs' genre rows. A genre the cloud hasn't read yet
     * has no Titles.
     */
    val genres: List<GenreList> = (tree?.get("genres") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { genre ->
        val kind = kind(genre["kind"]) ?: return@mapNotNull null
        val name = genre["name"] as? String ?: return@mapNotNull null
        GenreList(kind, name, (genre["tmdbIds"] as? List<*>).orEmpty().filterIsInstance<Number>().map { TitleId.Tmdb(kind, it.toLong()) })
    }

    class GenreList(val kind: TitleKind, val name: String, val titles: List<TitleId.Tmdb>)

    private fun kind(kind: Any?) = when (kind) {
        "movie" -> TitleKind.Movie
        "show" -> TitleKind.Show
        else -> null
    }

    private fun titles(list: Any?): List<TitleId.Tmdb> = (list as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { title ->
        val kind = kind(title["kind"]) ?: return@mapNotNull null
        (title["tmdbId"] as? Number)?.toLong()?.let { TitleId.Tmdb(kind, it) }
    }

    companion object {
        val NONE = Charts("{}")
    }
}
