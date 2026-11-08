package dev.betteriptv.core

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

/**
 * Speaks the Xtream Codes `player_api`. Responses are read as they arrive (OkHttp asks for gzip and
 * inflates on the fly), so no whole JSON document is ever held in memory.
 *
 * The login travels in every URL, so no URL or raw error message leaves this class: failures become
 * [ProviderException]s with the credentials scrubbed out.
 */
internal class XtreamClient(private val login: ProviderLogin, private val http: OkHttpClient) {
    private val base: HttpUrl = baseUrl(login.address)

    /** Checks the login and the subscription; throws a [ProviderException] when either is refused. */
    fun account(): LoggedIn {
        var auth: String? = null
        var status: String? = null
        var expiresOn: Instant? = null
        api(null) { json ->
            // A wrong login gets `{"user_info":{"auth":0}}`, or from some Providers an empty list.
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return@api json.skipValue()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "user_info" || json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
                json.beginObject()
                while (json.hasNext()) when (json.nextName()) {
                    "auth" -> auth = json.nextScalar()
                    "status" -> status = json.nextScalar()
                    "exp_date" -> expiresOn = json.nextScalar()?.toLongOrNull()?.let(Instant::ofEpochSecond)
                    else -> json.skipValue()
                }
                json.endObject()
            }
            json.endObject()
        }
        if (auth != "1") throw ProviderException(WrongLogin)
        if (status.equals("Expired", ignoreCase = true)) throw ProviderException(Expired(expiresOn))
        return LoggedIn(expiresOn)
    }

    /** Streams the movie Categories as (Provider category id, name). */
    fun movieCategories(each: (id: String, name: String) -> Unit) = categories("get_vod_categories", each)

    /** Streams the show Categories as (Provider category id, name). */
    fun showCategories(each: (id: String, name: String) -> Unit) = categories("get_series_categories", each)

    /** Streams the live Categories as (Provider category id, name). */
    fun liveCategories(each: (id: String, name: String) -> Unit) = categories("get_live_categories", each)

    /**
     * Streams the live Listings in the Provider's order as (Provider stream id, name, category id,
     * logo URL, guide id). The guide id (`epg_channel_id`) is what the Provider's guide knows the
     * Channel by, empty for the Channels its guide doesn't cover.
     */
    fun liveListings(each: (id: Long, name: String, categoryId: String?, image: String?, guideId: String) -> Unit) = api("get_live_streams") { json ->
        json.eachObject {
            var id: Long? = null
            var name: String? = null
            var categoryId: String? = null
            var image: String? = null
            var guideId = ""
            while (hasNext()) when (nextName()) {
                "stream_id" -> id = nextScalar()?.toLongOrNull()
                "name" -> name = nextScalar()
                "category_id" -> categoryId = nextScalar()
                "stream_icon" -> image = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                "epg_channel_id" -> guideId = nextScalar()?.trim().orEmpty()
                else -> skipValue()
            }
            if (id != null && name != null) each(id, name, categoryId, image, guideId)
        }
    }

    private fun categories(action: String, each: (id: String, name: String) -> Unit) = api(action) { json ->
        json.eachObject {
            var id: String? = null
            var name: String? = null
            while (hasNext()) when (nextName()) {
                "category_id" -> id = nextScalar()
                "category_name" -> name = nextScalar()
                else -> skipValue()
            }
            id?.let { each(it, name.orEmpty()) }
        }
    }

    /**
     * Streams the movie Listings as (Provider id, name, category id, added in epoch seconds, Title
     * number from its `tmdb` field, image URL, file extension, the Provider's rating). A number that isn't positive (empty,
     * `0`, an IMDb `tt…` ID) is null, and so is an extension that isn't a short word (`mkv`, `mp4`).
     * The rating is on a 10 scale; absent or zero is null.
     */
    fun movieListings(
        each: (id: Long, name: String, categoryId: String?, added: Long?, titleNumber: Long?, image: String?, container: String?, rating: Double?) -> Unit,
    ) = api("get_vod_streams") { json ->
        json.eachObject {
            var id: Long? = null
            var name: String? = null
            var categoryId: String? = null
            var added: Long? = null
            var titleNumber: Long? = null
            var image: String? = null
            var container: String? = null
            var rating: Double? = null
            while (hasNext()) when (nextName()) {
                "stream_id" -> id = nextScalar()?.toLongOrNull()
                "name" -> name = nextScalar()
                "category_id" -> categoryId = nextScalar()
                "added" -> added = nextScalar()?.toLongOrNull()
                "tmdb" -> titleNumber = nextScalar()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                "stream_icon" -> image = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                "container_extension" -> container = nextScalar()?.trim()?.lowercase()?.takeIf { CONTAINER.matches(it) }
                "rating" -> rating = rating(nextScalar())
                else -> skipValue()
            }
            if (id != null && name != null) each(id, name, categoryId, added, titleNumber, image, container, rating)
        }
    }

    /**
     * Streams the show Listings ([ProviderShowListing]): the Provider show id, name, category id, last
     * changed in epoch seconds (the Provider changes a Show when it adds Episodes to it), Title number,
     * image URL, and what the Listing says of the Show: plot, genre, cast, director, rating, release
     * date, backdrops and trailer key.
     */
    fun showListings(each: (ProviderShowListing) -> Unit) = api("get_series") { json ->
        json.eachObject {
            var id: Long? = null
            var name: String? = null
            var categoryId: String? = null
            var modified: Long? = null
            var titleNumber: Long? = null
            var image: String? = null
            var plot: String? = null
            var genre: String? = null
            var cast: String? = null
            var director: String? = null
            var rating: Double? = null
            var releaseDate: String? = null
            var backdrops: List<String> = emptyList()
            var trailer: String? = null
            while (hasNext()) when (nextName()) {
                "series_id" -> id = nextScalar()?.toLongOrNull()
                "name" -> name = nextScalar()
                "category_id" -> categoryId = nextScalar()
                "last_modified" -> modified = nextScalar()?.toLongOrNull()
                "tmdb" -> titleNumber = nextScalar()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                "cover" -> image = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                "plot" -> plot = cleanText(nextScalar())
                "genre" -> genre = cleanText(nextScalar())
                "cast" -> cast = cleanText(nextScalar())
                "director" -> director = cleanText(nextScalar())
                "rating" -> rating = rating(nextScalar())
                "releaseDate", "release_date" -> releaseDate = releaseDate(nextScalar()) ?: releaseDate
                "backdrop_path" -> backdrops = readImages()
                "youtube_trailer" -> trailer = trailerKey(nextScalar())
                else -> skipValue()
            }
            if (id != null && name != null) {
                each(ProviderShowListing(id, name, categoryId, modified, titleNumber, image, ProviderDetails(plot, genre, releaseDate, null, director, cast, rating, backdrops.firstOrNull(), trailer, backdrops)))
            }
        }
    }

    /** A list of image URLs (`backdrop_path`), or the one URL some Providers send bare; blanks left out. */
    private fun JsonReader.readImages(): List<String> = when (peek()) {
        JsonReader.Token.BEGIN_ARRAY -> buildList {
            beginArray()
            while (hasNext()) nextScalar()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            endArray()
        }
        else -> listOfNotNull(nextScalar()?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The Episodes in one show Listing's show details. Providers send them as an object of
     * seasons (`{"1": [...]}`) or as a list, of seasons or of Episodes; an Episode's own season
     * number wins over the one it's filed under.
     */
    fun showDetails(showId: Long): List<ProviderEpisode> = api("get_series_info", "series_id" to showId.toString()) { json ->
        val episodes = mutableListOf<ProviderEpisode>()
        if (json.peek() != JsonReader.Token.BEGIN_OBJECT) {
            json.skipValue()
            return@api episodes
        }
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "episodes") { json.skipValue(); continue }
            json.readEpisodes(season = null, episodes)
        }
        json.endObject()
        episodes
    }

    private fun JsonReader.readEpisodes(season: Int?, into: MutableList<ProviderEpisode>) {
        when (peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) {
                    val key = nextName()
                    readEpisodes(key.toIntOrNull(), into)
                }
                endObject()
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) {
                    when (peek()) {
                        JsonReader.Token.BEGIN_ARRAY -> readEpisodes(season, into)
                        JsonReader.Token.BEGIN_OBJECT -> readEpisode(season)?.let(into::add)
                        else -> skipValue()
                    }
                }
                endArray()
            }
            else -> skipValue()
        }
    }

    private fun JsonReader.readEpisode(filedUnder: Int?): ProviderEpisode? {
        var id: Long? = null
        var season: Int? = null
        var number: Int? = null
        var title: String? = null
        var added: Long? = null
        var width: Int? = null
        var height: Int? = null
        var audioLanguage: String? = null
        var container: String? = null
        var still: String? = null
        var durationSeconds: Int? = null
        var durationText: String? = null
        beginObject()
        while (hasNext()) when (nextName()) {
            "id" -> id = nextScalar()?.toLongOrNull()
            "season" -> season = nextScalar()?.toIntOrNull()
            "episode_num" -> number = nextScalar()?.toIntOrNull()
            "title" -> title = nextScalar()
            "added" -> added = nextScalar()?.toLongOrNull()
            "container_extension" -> container = nextScalar()?.trim()?.lowercase()?.takeIf { CONTAINER.matches(it) }
            // An Episode without details has `"info": []`.
            "info" -> if (peek() != JsonReader.Token.BEGIN_OBJECT) skipValue() else {
                beginObject()
                while (hasNext()) when (nextName()) {
                    "video" -> readObject { field ->
                        when (field) {
                            "width" -> width = nextScalar()?.toIntOrNull()
                            "height" -> height = nextScalar()?.toIntOrNull()
                            else -> skipValue()
                        }
                    }
                    "audio" -> readObject { field ->
                        if (field == "tags") readObject { tag -> if (tag == "language") audioLanguage = nextScalar() else skipValue() } else skipValue()
                    }
                    "movie_image" -> still = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                    "duration_secs" -> durationSeconds = nextScalar()?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    "duration" -> durationText = nextScalar()
                    else -> skipValue()
                }
                endObject()
            }
            else -> skipValue()
        }
        endObject()
        val inSeason = season ?: filedUnder
        if (id == null || number == null || inSeason == null) return null
        return ProviderEpisode(id, inSeason, number, title.orEmpty(), added, width, height, audioLanguage, container, still, durationSeconds ?: clockSeconds(durationText))
    }

    /** Reads an object's fields with [field], or skips the value when it isn't an object. */
    private inline fun JsonReader.readObject(field: JsonReader.(String) -> Unit) {
        if (peek() != JsonReader.Token.BEGIN_OBJECT) return skipValue()
        beginObject()
        while (hasNext()) field(nextName())
        endObject()
    }

    /**
     * The Provider's movie details for movie Listing [id] (`get_vod_info`): what it says of the Movie
     * (plot, genre, release date, runtime, director, cast, rating, backdrop, trailer key), and the
     * bitrate in bits per second from its own reading of the file (the details give kilobits per
     * second), each null when it doesn't say. A movie without details has `"info": []`.
     */
    fun movieDetails(id: Long): ProviderMovieDetails = api("get_vod_info", "vod_id" to id.toString()) { json ->
        var bitrate: Long? = null
        var plot: String? = null
        var genre: String? = null
        var releaseDate: String? = null
        var director: String? = null
        var cast: String? = null
        var actors: String? = null
        var rating: Double? = null
        var backdrops: List<String> = emptyList()
        var trailer: String? = null
        var durationText: String? = null
        var durationSeconds: Int? = null
        var runTime: Int? = null
        json.readObject { field ->
            if (field != "info") return@readObject skipValue()
            readObject { info ->
                when (info) {
                    "bitrate" -> bitrate = nextScalar()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
                    "plot", "description" -> plot = cleanText(nextScalar()) ?: plot
                    "genre" -> genre = cleanText(nextScalar())
                    "releasedate", "release_date", "releaseDate" -> releaseDate = releaseDate(nextScalar()) ?: releaseDate
                    "director" -> director = cleanText(nextScalar())
                    "cast" -> cast = cleanText(nextScalar())
                    "actors" -> actors = cleanText(nextScalar())
                    "rating" -> rating = rating(nextScalar())
                    "backdrop_path" -> backdrops = readImages()
                    "youtube_trailer" -> trailer = trailerKey(nextScalar())
                    "duration" -> durationText = nextScalar()
                    "duration_secs" -> durationSeconds = nextScalar()?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    "episode_run_time" -> runTime = nextScalar()?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    else -> skipValue()
                }
            }
        }
        // The surveyed Provider's `duration_secs` is sometimes minutes (`"110"` beside `"01:50:00"`): the clock reads first.
        val minutes = clockSeconds(durationText)?.let { (it + 30) / 60 }?.takeIf { it > 0 } ?: runTime ?: durationSeconds?.let { (it + 30) / 60 }?.takeIf { it > 0 }
        ProviderMovieDetails(bitrate, ProviderDetails(plot, genre, releaseDate, minutes, director, cast ?: actors, rating, backdrops.firstOrNull(), trailer, backdrops))
    }

    /** The Provider's `rating`, on a 10 scale; null when absent, unreadable or zero. */
    private fun rating(raw: String?): Double? = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }

    /** A release date as `yyyy-mm-dd`, from the Provider's `2011-04-17 (USA - UK)` or a bare year; null when it names none. */
    private fun releaseDate(raw: String?): String? = raw?.trim()?.let { DATE.find(it)?.value }

    /** `01:52:02` as seconds; null when the text isn't a clock. */
    private fun clockSeconds(raw: String?): Int? {
        val match = CLOCK.matchEntire(raw?.trim().orEmpty()) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return (hours.toInt() * 3600 + minutes.toInt() * 60 + seconds.toInt()).takeIf { it > 0 }
    }

    /**
     * A YouTube video ID from the Provider's `youtube_trailer`: the ID itself, or the one in a
     * YouTube link (`https://www.youtube.com/watch?v=…`, `https://youtu.be/…`); null when it is blank
     * or isn't one.
     */
    private fun trailerKey(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = when {
            "://" !in text -> text
            else -> runCatching { text.toHttpUrl() }.getOrNull()?.let { url -> url.queryParameter("v") ?: url.pathSegments.lastOrNull { it.isNotEmpty() } }
        }
        return key?.takeIf { YOUTUBE_ID.matches(it) }
    }

    /** Where movie Listing [id]'s file streams from. It holds the login, so it goes to the player engine only. */
    fun movieLocation(id: Long, container: String): String =
        base.newBuilder().addPathSegment("movie").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$container").build().toString()

    /** Where Episode [id]'s file streams from. It holds the login, so it goes to the player engine only. */
    fun episodeLocation(id: Long, container: String): String =
        base.newBuilder().addPathSegment("series").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$container").build().toString()

    /**
     * The Provider's guide for Channel variant [streamId] (its full table, `get_simple_data_table`):
     * up to [limit] programmes from the one on at [now], in order, their titles decoded from the
     * base64 the Provider sends (a title that isn't base64 is kept as it came). The short guide
     * (`get_short_epg`) would be ~2 KB rather than ~80 KB, but the surveyed Provider leaves out of it
     * every programme ending within the next hour, the one on now among them (#306). A programme whose
     * times the Provider doesn't give as timestamps is left out. Throws [GuideRefused] when the
     * Provider answers with anything but a guide (a 4xx, a 5xx), so the caller can stop asking for the
     * day; the usual [ProviderException] when it doesn't answer at all.
     */
    fun guide(streamId: Long, now: Instant, limit: Int): List<Programme> = api(
        "get_simple_data_table", "stream_id" to streamId.toString(),
        refused = { status -> GuideRefused(status) },
    ) { json ->
        val programmes = mutableListOf<Programme>()
        fun readListings() = json.eachObject {
            var title: String? = null
            var start: Long? = null
            var end: Long? = null
            while (hasNext()) when (nextName()) {
                "title" -> title = nextScalar()
                "start_timestamp" -> start = nextScalar()?.toLongOrNull()
                "stop_timestamp" -> end = nextScalar()?.toLongOrNull()
                else -> skipValue()
            }
            // The table runs from days ago to days ahead: only the programmes from now on are kept.
            if (title != null && start != null && end != null && end > start && end > now.epochSecond && programmes.size < limit) {
                programmes += Programme(decodeTitle(title), Instant.ofEpochSecond(start), Instant.ofEpochSecond(end))
            }
        }
        when (json.peek()) {
            // Some Providers answer the list bare.
            JsonReader.Token.BEGIN_ARRAY -> readListings()
            JsonReader.Token.BEGIN_OBJECT -> json.readObject { field -> if (field == "epg_listings" && peek() == JsonReader.Token.BEGIN_ARRAY) readListings() else skipValue() }
            else -> json.skipValue()
        }
        programmes
    }

    /** A guide title as the Provider sends it, base64 (`TGl2ZTogTkhMIEhvY2tleQ==`), decoded; left as it is when it isn't base64. */
    private fun decodeTitle(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            String(Base64.getMimeDecoder().decode(trimmed), Charsets.UTF_8).trim().ifEmpty { trimmed }
        } catch (e: IllegalArgumentException) {
            trimmed
        }
    }

    /**
     * Where Channel [id] streams from, as MPEG-TS (`ts`) or HLS (`m3u8`). It holds the login, so it
     * goes to the player engine only.
     */
    fun liveLocation(id: Long, format: String): String =
        base.newBuilder().addPathSegment("live").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$format").build().toString()

    /**
     * One `player_api` call. A response that isn't a success becomes an [Unreachable]
     * [ProviderException], unless [refused] makes something else of its status (a guide call refused).
     */
    private fun <T> api(action: String?, vararg parameters: Pair<String, String>, refused: ((Int) -> IOException)? = null, read: (JsonReader) -> T): T {
        val url = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", login.username)
            .addQueryParameter("password", login.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // The Viewer's Provider answers a wrong password with 513 and a missing login with 511.
                if (action == null && response.code in setOf(401, 403, 511, 513)) throw ProviderException(WrongLogin)
                if (!response.isSuccessful && refused != null) throw refused(response.code)
                if (!response.isSuccessful) throw ProviderException(Unreachable("The Provider answered HTTP ${response.code}."))
                return JsonReader.of(response.body!!.source()).use(read)
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: GuideRefused) {
            throw e
        } catch (e: IOException) {
            throw unreachable(e)
        } catch (e: JsonDataException) {
            throw unreachable(e)
        }
    }

    private fun unreachable(e: Exception): ProviderException {
        val detail = scrub(e.message ?: "").ifBlank { e.javaClass.simpleName }
        return ProviderException(Unreachable("Couldn't read from the Provider: $detail"))
    }

    /** Removes the login from [text], also in the percent-encoded forms a URL carries it in. */
    private fun scrub(text: String): String {
        val secrets = listOf(login.password, login.username).filter { it.isNotEmpty() }.flatMap {
            val encoded = URLEncoder.encode(it, "UTF-8")
            listOf(it, encoded, encoded.replace("+", "%20"))
        }
        return secrets.sortedByDescending { it.length }.fold(text) { acc, secret -> acc.replace(secret, "***", ignoreCase = true) }
    }

    companion object {
        private val CONTAINER = Regex("""[a-z0-9]{1,5}""")
        private val DATE = Regex("""\d{4}(-\d{2}(-\d{2})?)?""")
        private val CLOCK = Regex("""(\d{1,3}):(\d{2}):(\d{2})(?:\.\d+)?""")
        private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{6,20}""")

        /** Accepts a bare host, host:port, or a pasted player link, like the survey script. */
        fun baseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim()
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return try {
                withScheme.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build()
            } catch (e: IllegalArgumentException) {
                throw ProviderException(Unreachable("That Provider address doesn't look right."))
            }
        }
    }
}

internal class ProviderException(val problem: ProviderProblem) : IOException(problem.toString())

/**
 * One Episode in a show Listing's show details. [width], [height] and [audioLanguage] (the first
 * audio Track's, as the Provider writes it: ISO 639-2 `ger` or `deu`, or `und`) come from the
 * Provider reading the file; null when it didn't say. [still] is the Episode's picture URL
 * (`movie_image`) and [durationSeconds] its length, as the Provider's details give them; null without.
 */
internal class ProviderEpisode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val added: Long?,
    val width: Int?,
    val height: Int?,
    val audioLanguage: String?,
    /** The file's extension (`mkv`, `mp4`), which its location needs. */
    val container: String? = null,
    val still: String? = null,
    val durationSeconds: Int? = null,
) {
    /** The Episode's runtime in whole minutes, rounded; null when the Provider gave no length. */
    val runtimeMinutes: Int? get() = durationSeconds?.let { (it + 30) / 60 }?.takeIf { it > 0 }
}

/**
 * What the Provider says of a Title, on a show Listing or in a Movie's details (ADR 0014): its
 * [plot], its [genre] field as written (`Sci-Fi & Fantasy / Drama`, mapped to genres by the Prefix
 * table), its [releaseDate] (`yyyy-mm-dd`, or just the year), its [runtimeMinutes], its [director]
 * and [cast] as written (comma-separated names), its [rating] on a 10 scale (null when absent or
 * zero), its [backdrop] (the first of its [backdrops]) and its YouTube [trailer] key. Each null when
 * the Provider doesn't say.
 */
internal data class ProviderDetails(
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val runtimeMinutes: Int? = null,
    val director: String? = null,
    val cast: String? = null,
    val rating: Double? = null,
    val backdrop: String? = null,
    val trailer: String? = null,
    val backdrops: List<String> = emptyList(),
) {
    /** The release year, from [releaseDate]. */
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()

    /** Whether the Provider said anything at all. */
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = ProviderDetails()
    }
}

/** A Movie's details as the Provider gives them (`get_vod_info`): what it says of the Movie, and the file's [bitrate] in bits per second. */
internal class ProviderMovieDetails(val bitrate: Long?, val details: ProviderDetails)

/** One show Listing as the Provider lists it (`get_series`), with what it says of the Show in [details]. */
internal class ProviderShowListing(
    val id: Long,
    val name: String,
    val categoryId: String?,
    val modified: Long?,
    val titleNumber: Long?,
    val image: String?,
    val details: ProviderDetails,
)

/** Reads any JSON scalar as text: the Provider sends ids and dates as strings or numbers, inconsistently. */
internal fun JsonReader.nextScalar(): String? = when (peek()) {
    JsonReader.Token.NULL -> nextNull<String>()
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> nextString()
    JsonReader.Token.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

internal inline fun JsonReader.eachObject(read: JsonReader.() -> Unit) {
    if (peek() != JsonReader.Token.BEGIN_ARRAY) throw ProviderException(Unreachable("The Provider sent something other than a list."))
    beginArray()
    while (hasNext()) {
        if (peek() != JsonReader.Token.BEGIN_OBJECT) { skipValue(); continue }
        beginObject()
        read()
        endObject()
    }
    endArray()
}
