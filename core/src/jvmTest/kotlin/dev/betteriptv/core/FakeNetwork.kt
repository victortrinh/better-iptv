package dev.betteriptv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/**
 * The fakes' network (#580). A fake Provider or Cloud is a [FakeHost]: it answers a [FakeRequest] with
 * a [FakeAnswer], and knows nothing of how the request reached it. A [FakeTransport] puts it somewhere
 * requests reach: [InProcess], inside the Core's HTTP client with no socket at all, which is what the
 * Core's tests use and what lets them run where no local server can be opened (Apple, JavaScript); or
 * `OverSockets` (SocketHosts.kt), a real local server, for the few JVM tests of what only exists on a
 * socket and for an emulator's app.
 */
fun interface FakeHost {
    /** Answers [request]. Throwing an [IOException] closes the connection without an answer, as an unreachable host does. */
    suspend fun answer(request: FakeRequest): FakeAnswer
}

/** A request as a fake host gets it, whichever way it came: its [path] decoded, its header names as `Content-type`. */
class FakeRequest(val method: String, val path: String, rawQuery: String, headers: Map<String, List<String>>, val body: String = "") {
    val headers: Map<String, List<String>> = headers.mapKeys { (name, _) -> headerName(name) }
    val query: Map<String, String> = parseQueryString(rawQuery).let { asked -> asked.names().associateWith { asked[it].orEmpty() } }

    fun header(name: String): String? = headers[headerName(name)]?.firstOrNull()

    private fun headerName(name: String) = name.lowercase().replaceFirstChar(Char::uppercase)
}

/**
 * A fake host's answer: its [status] and [headers], then what [body] sends, a piece at a time, for as
 * long as it runs. A body that throws breaks the answer off midway; one whose client goes away is
 * cancelled. No [body] is an answer without one. [length] is the `Content-Length` it announces, and
 * [gzip] says a transport may compress it for a client that asks, as the Provider does its catalog.
 */
class FakeAnswer(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val length: Long? = null,
    val gzip: Boolean = false,
    val body: (suspend (FakeBody) -> Unit)? = null,
)

/** Where a fake host writes its answer's body; each piece goes to the client as it is written. */
fun interface FakeBody {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
}

suspend fun FakeBody.write(bytes: ByteArray) = write(bytes, 0, bytes.size)

/** How requests reach the fake hosts: where they listen, what a Library reaches them with, and how a player engine asks. */
interface FakeTransport {
    /** Puts [host] where requests reach it, until closed. */
    fun listen(host: FakeHost): Listening

    /** What a Library is opened with to reach the hosts listening here. */
    fun network(): CoreNetwork

    /** Asks for the file at [location], as a player engine does: outside the Core's client, with a [range] or without. */
    fun request(location: String, range: String? = null): EngineRequest
}

/** A fake host where requests reach it, at [url]. */
interface Listening {
    val url: String
    fun close()
}

/** A request a player engine made for a file: the [status] it was answered with and its [bytes], until the engine [close]s it. */
interface EngineRequest {
    val status: Int
    val bytes: InputStream
    fun close()
}

/**
 * The fake hosts inside the Core's HTTP client: nothing listens anywhere. Each host gets an address
 * like a local server's, `http://127.0.0.1:<port>`, with a port only this table knows, so the tests
 * that write a host as `localhost` or look for `127.0.0.1` in what was sent read as they did.
 */
object InProcess : FakeTransport {
    private val hosts = ConcurrentHashMap<Int, FakeHost>()
    private val lastPort = AtomicInteger(FIRST_PORT - 1)

    init {
        NoSockets.arm()
    }

    override fun listen(host: FakeHost): Listening {
        var port: Int
        do {
            port = lastPort.updateAndGet { if (it >= LAST_PORT) FIRST_PORT else it + 1 }
        } while (hosts.putIfAbsent(port, host) != null)
        return object : Listening {
            override val url = "http://127.0.0.1:$port"
            override fun close() {
                hosts.remove(port)
                NoSockets.check()
            }
        }
    }

    /** A client of its own for each Library: Ktor's mock engine keeps every request it was ever sent. */
    override fun network() = CoreNetwork(client()) { location -> FakeRelay(location) }

    /** The Core's client as [coreHttp] sets it up, on Ktor's mock engine: every request goes to the host at its port. */
    fun client(): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val opened = ask(request.url.toString(), request.method.value, headers(request), request.body.toByteArray().decodeToString())
                // The answer stops being sent the moment the Core lets go of it, as a closed connection stops a server.
                request.executionContext.invokeOnCompletion { opened.close() }
                respond(opened.bytes, HttpStatusCode.fromValue(opened.status), HeadersBuilder().apply { opened.headers.forEach(::append) }.build())
            }
        }
        install(HttpTimeout)
        install(HttpRedirect) { allowHttpsDowngrade = true }
        expectSuccess = false
    }

    private fun headers(request: HttpRequestData): Map<String, List<String>> =
        request.headers.entries().associate { it.key to it.value } + listOfNotNull(request.body.contentType?.let { "Content-Type" to listOf(it.toString()) })

    override fun request(location: String, range: String?): EngineRequest {
        val opened = runBlocking { ask(location, "GET", listOfNotNull(range?.let { "Range" to listOf(it) }).toMap()) }
        return object : EngineRequest {
            override val status = opened.status
            override val bytes = opened.bytes.toInputStream()
            override fun close() = opened.close()
        }
    }

    /** Sends one request to the host at [location]'s port and opens its answer. */
    internal suspend fun ask(location: String, method: String, headers: Map<String, List<String>>, body: String = ""): OpenedAnswer {
        val url = Url(location)
        val host = hosts[url.port] ?: throw IOException("Connection refused: nothing listens at ${url.host}:${url.port}")
        return host.answer(FakeRequest(method, url.encodedPath.decodeURLPart(), url.encodedQuery, headers, body)).open()
    }

    private const val FIRST_PORT = 20_000
    private const val LAST_PORT = 60_000
}

/** An answer being sent: its [bytes] arrive as the host writes them, until the reader [close]s it. */
internal class OpenedAnswer(val status: Int, val headers: Map<String, String>, val bytes: ByteReadChannel, private val sending: Job?) {
    fun close() {
        sending?.cancel()
        bytes.cancel()
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun FakeAnswer.open(): OpenedAnswer {
    val announced = headers + listOfNotNull(length?.let { "Content-Length" to it.toString() })
    val send = body ?: return OpenedAnswer(status, announced, ByteReadChannel.Empty, null)
    val channel = ByteChannel()
    // Started even when its reader has already gone, so a host always sees its answer end (and gives its stream back).
    val sending = GlobalScope.launch(Dispatchers.IO, CoroutineStart.ATOMIC) {
        try {
            send { bytes, offset, length ->
                channel.writeFully(bytes, offset, offset + length)
                channel.flush()
            }
            channel.flushAndClose()
        } catch (e: CancellationException) {
            channel.cancel(e)
            throw e
        } catch (e: Throwable) {
            channel.close(IOException("the answer broke off midway", e))
        }
    }
    return OpenedAnswer(status, announced, channel, sending)
}

/**
 * A [StreamRelay] with no socket, standing in for the platform's ([LocalStreamRelay] on Android): a
 * host of its own whose location holds no login, which asks the Provider for the file one request at
 * a time. A new request closes the earlier one first and waits out the Provider's release grace, as
 * the real relay does, or a one-stream Provider would refuse it.
 */
private class FakeRelay(private val upstream: String) : StreamRelay, FakeHost {
    private val listening = InProcess.listen(this)
    override val location = "${listening.url}/file"
    @Volatile override var answeredAt: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    /** One of the engine's requests, and the Provider's answer to it once there is one. */
    private class Asked {
        var answer: OpenedAnswer? = null
        var closed = false
    }

    private var current: Asked? = null
    private val lock = Any()

    override suspend fun answer(request: FakeRequest): FakeAnswer {
        val asked = Asked()
        val earlier = synchronized(lock) { current.also { current = asked } }
        if (earlier != null) {
            synchronized(lock) { earlier.closed = true; earlier.answer }?.close()
            delay(PROVIDER_RELEASE_GRACE)
        }
        val answer = InProcess.ask(upstream, "GET", listOfNotNull(request.header("Range")?.let { "Range" to listOf(it) }).toMap())
        if (synchronized(lock) { asked.answer = answer; asked.closed }) {
            answer.close()
            throw IOException("a newer request took the Provider stream")
        }
        return FakeAnswer(answer.status, answer.headers.filterKeys { it in PASSED_ON }) { body ->
            try {
                val buffer = ByteArray(64 * 1024)
                var answered = false
                while (true) {
                    val read = answer.bytes.readAvailable(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (!answered) {
                        answered = true
                        answeredAt = TimeSource.Monotonic.markNow()
                    }
                    body.write(buffer, 0, read)
                }
            } finally {
                answer.close()
            }
        }
    }

    override fun close() {
        listening.close()
        synchronized(lock) { current?.also { it.closed = true }?.answer }?.close()
    }

    private companion object {
        val PASSED_ON = setOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified")
    }
}
