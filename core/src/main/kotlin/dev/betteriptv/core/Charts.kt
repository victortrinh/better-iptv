package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * The cloud's Charts answer for a Region (cloud/src/charts.ts), as the device keeps it: Netflix's
 * Top 10 movies and shows as TMDB IDs in rank order, the world's when [worldwide], and TMDB's
 * trending this week. Parts this app doesn't read yet are kept in the JSON and left alone here.
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

    private fun titles(list: Any?): List<TitleId.Tmdb> = (list as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { title ->
        val kind = when (title["kind"]) {
            "movie" -> TitleKind.Movie
            "show" -> TitleKind.Show
            else -> return@mapNotNull null
        }
        (title["tmdbId"] as? Number)?.toLong()?.let { TitleId.Tmdb(kind, it) }
    }

    companion object {
        val NONE = Charts("{}")
    }
}
