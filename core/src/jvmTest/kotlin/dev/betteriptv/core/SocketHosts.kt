package dev.betteriptv.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * The fake hosts as real local servers, on the JVM only: for the tests of what exists only on a socket
 * (Android's relay, what OkHttp sends), and for an app on an emulator, whose player needs a real
 * address (ServedProviderTest). A Library reaches them with Android's own network, and a player engine
 * asks them over a connection of its own. Tests that use it carry the [RealSockets] rule.
 */
object OverSockets : FakeTransport {
    override fun listen(host: FakeHost): Listening {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> try { serve(host, exchange) } finally { exchange.close() } }
        server.start()
        return object : Listening {
            override val url = "http://127.0.0.1:${server.address.port}"
            override fun close() = server.stop(0)
        }
    }

    override fun network(): CoreNetwork = okHttpNetwork()

    override fun request(location: String, range: String?): EngineRequest {
        val connection = URI(location).toURL().openConnection() as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        return object : EngineRequest {
            override val status = connection.responseCode
            override val bytes: InputStream get() = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }

    private fun serve(host: FakeHost, exchange: HttpExchange) = runBlocking {
        val request = FakeRequest(
            exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery.orEmpty(), exchange.requestHeaders.toMap(),
            exchange.requestBody.readBytes().decodeToString(),
        )
        // A host that throws closes the connection without a status line.
        val answer = try { host.answer(request) } catch (_: IOException) { return@runBlocking }
        val gzip = answer.gzip && exchange.requestHeaders["Accept-Encoding"].orEmpty().any { "gzip" in it }
        answer.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        if (gzip) exchange.responseHeaders.add("Content-Encoding", "gzip")
        val send = answer.body ?: return@runBlocking exchange.sendResponseHeaders(answer.status, -1)
        exchange.sendResponseHeaders(answer.status, if (gzip) 0 else answer.length ?: 0)
        val body = if (gzip) GZIPOutputStream(exchange.responseBody, 64 * 1024) else exchange.responseBody
        try {
            send { bytes, offset, length ->
                body.write(bytes, offset, length)
                if (!gzip) body.flush()
            }
            body.close()
        } catch (_: IOException) {
            // The client went away, or the host broke its answer off: what was sent stays unfinished, so the client sees it truncated.
        }
    }
}

/** A test of real sockets, which runs on the JVM only ([RealSockets]). */
abstract class SocketTest {
    companion object {
        @ClassRule @JvmField val sockets = RealSockets()
    }
}
