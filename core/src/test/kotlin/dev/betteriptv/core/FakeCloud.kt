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

    private val metadata = HashMap<String, Map<String, Any?>>()
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
        get() = received.filter { it.path == "/v1/match" }.flatMap { request -> ((read(request.body) as Map<*, *>)["listings"] as List<*>).map { it as Map<*, *> } }

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
            "/v1/metadata", "/v1/match" -> {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                if (synchronized(tokens) { token !in tokens }) return respond(exchange, 401, mapOf("error" to "A registered device token is required."))
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
): Map<String, Any?> = mapOf(
    "kind" to kind,
    "tmdbId" to tmdbId,
    "originalLanguage" to originalLanguage,
    "originalTitle" to originalTitle,
    "titles" to titles.map { (language, region, title) -> mapOf("language" to language, "region" to region, "title" to title) },
    "alternativeTitles" to emptyList<String>(),
    "descriptions" to descriptions.map { (language, region, text) -> mapOf("language" to language, "region" to region, "text" to text) },
    "genres" to genres,
    "popularity" to 10.0,
    "releaseDate" to releaseDate,
    "runtimeMinutes" to runtimeMinutes,
    "posterPath" to posterPath,
    "backdropPath" to backdropPath,
    "cast" to cast.map { mapOf("name" to it, "character" to "", "profilePath" to null) },
    "imdbId" to null,
    "trailers" to emptyList<String>(),
)
