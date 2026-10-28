package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okio.buffer
import okio.source
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A local stand-in for the app's cloud (cloud/src/index.ts), serving canned Title metadata and
 * Match answers.
 * It hands out device tokens like the real one and refuses requests without one. Every request it
 * gets is recorded, so tests can check what the device sends.
 */
class FakeCloud : Closeable {
    /** One request as it arrived: path, headers and body. */
    class Received(val path: String, val headers: Map<String, List<String>>, val body: String)

    val received = CopyOnWriteArrayList<Received>()

    /** Answers every request with HTTP 503, as an unreachable cloud would. */
    @Volatile var down = false

    /** How many of the next `/v1/match` requests are answered with HTTP 503, as a Worker over its CPU limit would. */
    @Volatile var failingMatches = 0

    /** Called when a `/v1/match` request arrives, before it is answered. */
    @Volatile var onMatch: (() -> Unit)? = null

    private val metadata = HashMap<String, Map<String, Any?>>()
    private val searches = HashMap<String, Map<String, Any?>>()

    /** The queries the device sent to `/v1/search`, as sent. */
    val searchAsked: List<String>
        get() = received.filter { it.path == "/v1/search" }.map { (read(it.body) as Map<*, *>)["query"] as String }

    /** The file reads devices shared, by `<provider key>/<listing id>`. */
    val fileReads = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val matches = HashMap<String, Long?>()
    private val tokens = HashSet<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** The Titles the device asked metadata for, as `movie/238/fr`, one per Title per request. */
    val metadataAsked: List<String>
        get() = received.filter { it.path == "/v1/metadata" }.flatMap { request ->
            val body = read(request.body) as Map<*, *>
            (body["titles"] as List<*>).map { title -> (title as Map<*, *>).let { "${it["kind"]}/${(it["tmdbId"] as Number).toLong()}/${body["language"]}" } }
        }

    /** The Listings the device asked to Match, as sent: kind, name, year, language and tmdbId. */
    val matchAsked: List<Map<*, *>>
        get() = matchRequests.flatten()

    /** The same, one list per request, answered or not. */
    val matchRequests: List<List<Map<*, *>>>
        get() = received.filter { it.path == "/v1/match" }.map { request -> ((read(request.body) as Map<*, *>)["listings"] as List<*>).map { it as Map<*, *> } }

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> try { handle(exchange) } finally { exchange.close() } }
        server.start()
    }

    /** Serves [title] (built with [tmdbTitle]) for requests in [language]. */
    fun title(language: String, title: Map<String, Any?>) {
        synchronized(metadata) { metadata["${title["kind"]}/${title["tmdbId"]}/$language"] = title }
    }

    /**
     * Answers a Match for a Listing cleaned to [name] with [tmdbId] (null: TMDB has no Title by that
     * name), whatever ID the Provider gave it. Listings without an answer keep the Provider's ID.
     */
    fun match(name: String, tmdbId: Long?) {
        synchronized(matches) { matches[name] = tmdbId }
    }

    /** A downloadable subtitle file, as the cloud's subtitle search finds it. */
    class Subtitle(val kind: String, val tmdbId: Long, val season: Int?, val episode: Int?, val language: String, val release: String, val text: String) {
        val id = "opensubtitles:$language:${Math.abs((kind + tmdbId + season + episode + release).hashCode())}"
    }

    val subtitles = CopyOnWriteArrayList<Subtitle>()

    /** Offers [text] as a downloadable subtitle for the Movie [tmdbId] (or its [season]'s [episode]) in [language]. */
    fun subtitle(tmdbId: Long, language: String, release: String, text: String = "1\n00:00:01,000 --> 00:00:02,000\n$release\n", kind: String = "movie", season: Int? = null, episode: Int? = null) =
        Subtitle(kind, tmdbId, season, episode, language, release, text).also { subtitles += it }

    /**
     * Answers a TMDB search for [query] (any case) with [titles] (TMDB ID and popularity) and [people]
     * (name and their Titles). Other queries find nothing.
     */
    fun search(query: String, titles: List<Pair<TitleId.Tmdb, Double>> = emptyList(), people: List<Pair<String, List<TitleId.Tmdb>>> = emptyList()) {
        val key = { id: TitleId.Tmdb -> mapOf("kind" to if (id.kind == TitleKind.Movie) "movie" else "show", "tmdbId" to id.tmdbId) }
        synchronized(searches) {
            searches[query.lowercase()] = mapOf(
                "titles" to titles.map { (id, popularity) -> key(id) + ("popularity" to popularity) },
                "people" to people.map { (name, ids) -> mapOf("name" to name, "titles" to ids.map(key)) },
            )
        }
    }

    /** Forgets every device, as if its token were revoked. */
    fun forgetDevices() = synchronized(tokens) { tokens.clear() }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        received += Received(exchange.requestURI.path, exchange.requestHeaders.toMap(), body)
        if (down) return exchange.sendResponseHeaders(503, -1)
        when (exchange.requestURI.path) {
            "/v1/devices" -> {
                val token = UUID.randomUUID().toString()
                synchronized(tokens) { tokens += token }
                respond(exchange, 201, mapOf("token" to token))
            }
            "/v1/file-reads", "/v1/file-reads/lookup" -> {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (synchronized(tokens) { token !in tokens }) return respond(exchange, 401, mapOf("error" to "A registered device token is required."))
                val asked = read(body) as Map<*, *>
                val provider = asked["provider"] as String
                if (exchange.requestURI.path == "/v1/file-reads") {
                    (asked["files"] as List<*>).forEach { (it as Map<*, *>).let { file -> fileReads["$provider/${(file["listingId"] as Number).toLong()}"] = file["file"]!! } }
                    return exchange.sendResponseHeaders(204, -1)
                }
                val files = (asked["listings"] as List<*>).mapNotNull { id ->
                    val listing = (id as Number).toLong()
                    fileReads["$provider/$listing"]?.let { mapOf("listingId" to listing, "file" to it) }
                }
                respond(exchange, 200, mapOf("files" to files))
            }
            "/v1/search" -> {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (synchronized(tokens) { token !in tokens }) return respond(exchange, 401, mapOf("error" to "A registered device token is required."))
                val query = ((read(body) as Map<*, *>)["query"] as String).lowercase()
                respond(exchange, 200, synchronized(searches) { searches[query] } ?: mapOf("titles" to emptyList<Any>(), "people" to emptyList<Any>()))
            }
            "/v1/metadata", "/v1/match" -> {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (synchronized(tokens) { token !in tokens }) return respond(exchange, 401, mapOf("error" to "A registered device token is required."))
                if (exchange.requestURI.path == "/v1/match") onMatch?.invoke()
                if (exchange.requestURI.path == "/v1/match" && synchronized(this) { (failingMatches > 0).also { if (it) failingMatches-- } }) return exchange.sendResponseHeaders(503, -1)
                if (exchange.requestURI.path == "/v1/match") return respond(exchange, 200, mapOf("matches" to answerMatches(read(body) as Map<*, *>)))
                val asked = read(body) as Map<*, *>
                val language = asked["language"] as String
                val keys = (asked["titles"] as List<*>).map { (it as Map<*, *>).let { t -> "${t["kind"]}/${(t["tmdbId"] as Number).toLong()}" } }
                val found = synchronized(metadata) { keys.mapNotNull { metadata["$it/$language"] } }
                val notFound = keys.filter { key -> found.none { "${it["kind"]}/${it["tmdbId"]}" == key } }.map {
                    mapOf("kind" to it.substringBefore('/'), "tmdbId" to it.substringAfter('/').toLong())
                }
                respond(exchange, 200, mapOf("titles" to found, "notFound" to notFound, "unavailable" to emptyList<Any>()))
            }
            "/v1/subtitles", "/v1/subtitles/download" -> {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (synchronized(tokens) { token !in tokens }) return respond(exchange, 401, mapOf("error" to "A registered device token is required."))
                val asked = read(body) as Map<*, *>
                if (exchange.requestURI.path == "/v1/subtitles/download") {
                    val file = subtitles.find { it.id == asked["id"] } ?: return respond(exchange, 404, mapOf("error" to "The service no longer has that subtitle."))
                    return respond(exchange, 200, mapOf("format" to "srt", "text" to file.text))
                }
                val languages = asked["languages"] as List<*>
                val found = languages.flatMap { language ->
                    subtitles.filter {
                        it.kind == asked["kind"] && it.tmdbId == (asked["tmdbId"] as Number).toLong() && it.language == language &&
                            it.season == (asked["season"] as Number?)?.toInt() && it.episode == (asked["episode"] as Number?)?.toInt()
                    }
                }
                respond(exchange, 200, mapOf(
                    "subtitles" to found.map { mapOf("id" to it.id, "language" to it.language, "source" to "opensubtitles", "release" to it.release, "hearingImpaired" to false, "downloads" to 1) },
                    "unavailable" to emptyList<String>(),
                ))
            }
            else -> respond(exchange, 404, mapOf("error" to "No such endpoint."))
        }
    }

    private fun answerMatches(asked: Map<*, *>) = (asked["listings"] as List<*>).map { listing ->
        listing as Map<*, *>
        val name = listing["name"] as String
        val tmdbId = synchronized(matches) { if (name in matches) matches[name] else (listing["tmdbId"] as Number?)?.toLong() }
        mapOf("kind" to listing["kind"], "tmdbId" to tmdbId)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = json.toJson(body).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun read(text: String): Any? = JsonReader.of(text.byteInputStream().source().buffer()).use { it.readJsonValue() }

    private companion object {
        val json = Moshi.Builder().build().adapter(Any::class.java)
    }
}

/** A Title's metadata shaped like the cloud's answer, with only what a test cares about filled in. */
fun tmdbTitle(
    tmdbId: Long,
    originalTitle: String,
    kind: String = "movie",
    originalLanguage: String = "en",
    titles: List<Triple<String, String, String>> = emptyList(),
    descriptions: List<Triple<String, String, String>> = emptyList(),
    genres: List<String> = emptyList(),
    releaseDate: String? = null,
    runtimeMinutes: Int? = null,
    posterPath: String? = null,
    backdropPath: String? = null,
    cast: List<String> = emptyList(),
    popularity: Double = 10.0,
    alternativeTitles: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
    "kind" to kind,
    "tmdbId" to tmdbId,
    "originalLanguage" to originalLanguage,
    "originalTitle" to originalTitle,
    "titles" to titles.map { (language, region, title) -> mapOf("language" to language, "region" to region, "title" to title) },
    "alternativeTitles" to alternativeTitles,
    "descriptions" to descriptions.map { (language, region, text) -> mapOf("language" to language, "region" to region, "text" to text) },
    "genres" to genres,
    "popularity" to popularity,
    "releaseDate" to releaseDate,
    "runtimeMinutes" to runtimeMinutes,
    "posterPath" to posterPath,
    "backdropPath" to backdropPath,
    "cast" to cast.map { mapOf("name" to it, "character" to "", "profilePath" to null) },
    "imdbId" to null,
    "trailers" to emptyList<String>(),
)
