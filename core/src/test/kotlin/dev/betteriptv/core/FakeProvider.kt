package dev.betteriptv.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * A local Xtream Codes Provider that replays the sanitized fixture cut by scripts/cut_fixture.py.
 *
 * Like the surveyed Provider, it allows one stream at a time: a second concurrent stream is refused.
 * Tests script it to be down, expired, or to cut the movie list off midway.
 */
class FakeProvider : Closeable {
    val username = "viewer"
    val password = "s3cret-Pa55"

    /** When the subscription ends; in the past means expired. */
    @Volatile var expiresOn: Instant = Instant.parse("2030-01-01T00:00:00Z")

    /** Answers every request with HTTP 503, as an unreachable Provider would. */
    @Volatile var down = false

    /** Stops sending the movie list halfway through. */
    @Volatile var cutMovieListMidway = false

    /** Replaces the fixture's movie list with this many generated Listings, streamed as they're written. */
    @Volatile var generatedMovies: Int? = null

    /** Replaces the fixture's movie list with this one. */
    @Volatile var movieListOverride: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val openStreams = AtomicInteger()
    private val _streamRequests = AtomicInteger()
    private val _refusedStreams = AtomicInteger()
    private val _requestsWithoutGzip = AtomicInteger()

    /** Requests for a stream (movie file) location, from anyone. */
    val streamRequests get() = _streamRequests.get()
    val refusedStreams get() = _refusedStreams.get()
    val requestsWithoutGzip get() = _requestsWithoutGzip.get()

    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val login get() = ProviderLogin(url, username, password)

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/player_api.php") { exchange -> try { api(exchange) } finally { exchange.close() } }
        server.createContext("/movie/") { exchange -> try { stream(exchange) } finally { exchange.close() } }
        server.start()
    }

    /** Opens a movie stream the way the player would; close it to end playback. */
    fun openStream(streamId: Long): InputStream {
        val connection = URI("$url/movie/$username/$password/$streamId.mkv").toURL().openConnection() as HttpURLConnection
        if (connection.responseCode != 200) throw IllegalStateException("stream refused: HTTP ${connection.responseCode}")
        return connection.inputStream
    }

    override fun close() = server.stop(0)

    private fun api(exchange: HttpExchange) {
        if (down) return exchange.sendResponseHeaders(503, -1)
        val query = query(exchange)
        if (query["username"] != username || query["password"] != password) {
            return respond(exchange) { it.write("""{"user_info":{"auth":0}}""".toByteArray()) }
        }
        when (query["action"]) {
            null -> respond(exchange) { it.write(account().toByteArray()) }
            "get_vod_categories" -> respond(exchange) { it.write(fixture("vod_categories.json")) }
            "get_vod_streams" -> respond(exchange) { body -> movieList(body) }
            else -> respond(exchange) { it.write("[]".toByteArray()) }
        }
    }

    private fun account(): String {
        val status = if (expiresOn.isAfter(Instant.now())) "Active" else "Expired"
        return """{"user_info":{"username":"$username","auth":1,"status":"$status","exp_date":"${expiresOn.epochSecond}",""" +
            """"is_trial":"0","active_cons":"${openStreams.get()}","max_connections":"1"},"server_info":{"timezone":"UTC"}}"""
    }

    private fun movieList(body: OutputStream) {
        val generated = generatedMovies
        val override = movieListOverride
        when {
            generated != null -> generateMovies(body, generated)
            override != null -> body.write(override.toByteArray())
            cutMovieListMidway -> {
                val bytes = fixture("vod_streams.json")
                body.write(bytes, 0, bytes.size / 2)
                body.flush()
                throw CutOff()
            }
            else -> body.write(fixture("vod_streams.json"))
        }
    }

    private fun generateMovies(body: OutputStream, count: Int) {
        val writer = body.bufferedWriter()
        writer.write("[")
        for (i in 1..count) {
            if (i > 1) writer.write(",")
            // Shaped like the real list: about 400 bytes per Listing, which makes the full catalog ~56 MB.
            writer.write(
                """{"num":$i,"name":"EN - Generated Movie $i (2020)","stream_type":"movie","stream_id":$i,""" +
                    """"stream_icon":"","rating":"6.5","rating_5based":3.3,"tmdb":"$i","trailer":"",""" +
                    """"added":"${1_700_000_000 + i}","is_adult":0,"category_id":"1","category_ids":[1],""" +
                    """"container_extension":"mkv","custom_sid":null,"direct_source":"",""" +
                    """"padding":"${"x".repeat(150)}"}""",
            )
        }
        writer.write("]")
        writer.flush()
    }

    private fun stream(exchange: HttpExchange) {
        _streamRequests.incrementAndGet()
        val parts = exchange.requestURI.path.split("/")
        if (parts.getOrNull(2) != username || parts.getOrNull(3) != password) return exchange.sendResponseHeaders(401, -1)
        if (openStreams.incrementAndGet() > 1) {
            openStreams.decrementAndGet()
            _refusedStreams.incrementAndGet()
            return exchange.sendResponseHeaders(403, -1)
        }
        try {
            exchange.sendResponseHeaders(200, 0)
            val chunk = ByteArray(16 * 1024)
            // Keeps "playing" until the client goes away.
            while (true) {
                exchange.responseBody.write(chunk)
                exchange.responseBody.flush()
                Thread.sleep(20)
            }
        } catch (_: Exception) {
        } finally {
            openStreams.decrementAndGet()
        }
    }

    private fun respond(exchange: HttpExchange, write: (OutputStream) -> Unit) {
        val gzip = exchange.requestHeaders["Accept-Encoding"].orEmpty().any { "gzip" in it }
        if (!gzip) _requestsWithoutGzip.incrementAndGet()
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (gzip) exchange.responseHeaders.add("Content-Encoding", "gzip")
        exchange.sendResponseHeaders(200, 0)
        val body = if (gzip) GZIPOutputStream(exchange.responseBody, 64 * 1024) else exchange.responseBody
        try {
            write(body)
            body.close()
        } catch (_: CutOff) {
            // Leave the gzip stream unfinished so the client sees a truncated body.
        }
    }

    private class CutOff : Exception()

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { "=" in it }.associate {
            val (key, value) = it.split("=", limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixture/$name")!!.use { it.readBytes() }
}

/** Credentials kept in memory, standing in for the device's keystore-backed storage. */
class InMemoryCredentials : CredentialStore {
    var saved: ProviderLogin? = null
    override fun load() = saved
    override fun save(login: ProviderLogin) { saved = login }
    override fun clear() { saved = null }
}
