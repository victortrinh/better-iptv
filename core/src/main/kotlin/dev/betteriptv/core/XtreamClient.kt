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
     * Streams the movie Listings as (Provider id, name, category id, added in epoch seconds, TMDB ID,
     * image URL, file extension). A TMDB ID that isn't a positive number (empty, `0`, an IMDb `tt…`
     * ID) is null, and so is an extension that isn't a short word (`mkv`, `mp4`).
     */
    fun movieListings(
        each: (id: Long, name: String, categoryId: String?, added: Long?, tmdbId: Long?, image: String?, container: String?) -> Unit,
    ) = api("get_vod_streams") { json ->
        json.eachObject {
            var id: Long? = null
            var name: String? = null
            var categoryId: String? = null
            var added: Long? = null
            var tmdbId: Long? = null
            var image: String? = null
            var container: String? = null
            while (hasNext()) when (nextName()) {
                "stream_id" -> id = nextScalar()?.toLongOrNull()
                "name" -> name = nextScalar()
                "category_id" -> categoryId = nextScalar()
                "added" -> added = nextScalar()?.toLongOrNull()
                "tmdb" -> tmdbId = nextScalar()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                "stream_icon" -> image = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                "container_extension" -> container = nextScalar()?.trim()?.lowercase()?.takeIf { CONTAINER.matches(it) }
                else -> skipValue()
            }
            if (id != null && name != null) each(id, name, categoryId, added, tmdbId, image, container)
        }
    }

    /**
     * Streams the show Listings as (Provider show id, name, category id, last changed in epoch
     * seconds, TMDB ID, image URL). The Provider changes a Show when it adds Episodes to it.
     */
    fun showListings(each: (id: Long, name: String, categoryId: String?, modified: Long?, tmdbId: Long?, image: String?) -> Unit) = api("get_series") { json ->
        json.eachObject {
            var id: Long? = null
            var name: String? = null
            var categoryId: String? = null
            var modified: Long? = null
            var tmdbId: Long? = null
            var image: String? = null
            while (hasNext()) when (nextName()) {
                "series_id" -> id = nextScalar()?.toLongOrNull()
                "name" -> name = nextScalar()
                "category_id" -> categoryId = nextScalar()
                "last_modified" -> modified = nextScalar()?.toLongOrNull()
                "tmdb" -> tmdbId = nextScalar()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                "cover" -> image = nextScalar()?.trim()?.takeIf { it.isNotEmpty() }
                else -> skipValue()
            }
            if (id != null && name != null) each(id, name, categoryId, modified, tmdbId, image)
        }
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
                    else -> skipValue()
                }
                endObject()
            }
            else -> skipValue()
        }
        endObject()
        val inSeason = season ?: filedUnder
        if (id == null || number == null || inSeason == null) return null
        return ProviderEpisode(id, inSeason, number, title.orEmpty(), added, width, height, audioLanguage, container)
    }

    /** Reads an object's fields with [field], or skips the value when it isn't an object. */
    private inline fun JsonReader.readObject(field: JsonReader.(String) -> Unit) {
        if (peek() != JsonReader.Token.BEGIN_OBJECT) return skipValue()
        beginObject()
        while (hasNext()) field(nextName())
        endObject()
    }

    /**
     * The bitrate in bits per second the Provider's movie details give for movie Listing [id], from
     * its own reading of the file; null when they don't say. The details give kilobits per second.
     */
    fun movieBitrate(id: Long): Long? = api("get_vod_info", "vod_id" to id.toString()) { json ->
        var bitrate: Long? = null
        // A movie without details has `"info": []`.
        json.readObject { field ->
            if (field == "info") readObject { info -> if (info == "bitrate") bitrate = nextScalar()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) else skipValue() } else skipValue()
        }
        bitrate
    }

    /** Where movie Listing [id]'s file streams from. It holds the login, so it goes to the player engine only. */
    fun movieLocation(id: Long, container: String): String =
        base.newBuilder().addPathSegment("movie").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$container").build().toString()

    /** Where Episode [id]'s file streams from. It holds the login, so it goes to the player engine only. */
    fun episodeLocation(id: Long, container: String): String =
        base.newBuilder().addPathSegment("series").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$container").build().toString()

    /**
     * Where Channel [id] streams from, as MPEG-TS (`ts`) or HLS (`m3u8`). It holds the login, so it
     * goes to the player engine only.
     */
    fun liveLocation(id: Long, format: String): String =
        base.newBuilder().addPathSegment("live").addPathSegment(login.username).addPathSegment(login.password).addPathSegment("$id.$format").build().toString()

    private fun <T> api(action: String?, vararg parameters: Pair<String, String>, read: (JsonReader) -> T): T {
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
                if (!response.isSuccessful) throw ProviderException(Unreachable("The Provider answered HTTP ${response.code}."))
                return JsonReader.of(response.body!!.source()).use(read)
            }
        } catch (e: ProviderException) {
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
            val encoded = URLEncoder.encode(it, Charsets.UTF_8)
            listOf(it, encoded, encoded.replace("+", "%20"))
        }
        return secrets.sortedByDescending { it.length }.fold(text) { acc, secret -> acc.replace(secret, "***", ignoreCase = true) }
    }

    companion object {
        private val CONTAINER = Regex("""[a-z0-9]{1,5}""")

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
 * Provider reading the file; null when it didn't say.
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
