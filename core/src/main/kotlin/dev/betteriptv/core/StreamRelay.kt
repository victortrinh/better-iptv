package dev.betteriptv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.time.TimeSource

/**
 * Serves a Version's file to a player engine on this device through one Provider stream at a time.
 * VLC opens a new request for the file before closing the one it has (to read the index at its end,
 * and to seek), and a one-stream Provider refuses the second; the relay closes the earlier request
 * to the Provider first. Its [location] holds no login, so the engine can't log one.
 */
internal class StreamRelay(private val upstream: String, private val http: OkHttpClient) : Closeable {
    // Not getLoopbackAddress(): on Android that is IPv6 (::1), where an engine given 127.0.0.1 can't connect.
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val location = "http://127.0.0.1:${server.localPort}/file"

    /**
     * When the Provider last started sending the file's bytes for one of the engine's requests, or null
     * before it first did: VLC reads several parts of a file before its picture starts, and each is a
     * sign the file is alive.
     */
    @Volatile var answeredAt: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    /** The request to the Provider in use; a new one closes it first. */
    private var current: Call? = null
    private val lock = Any()

    init {
        thread(isDaemon = true, name = "stream-relay") {
            try {
                while (true) {
                    val client = server.accept()
                    thread(isDaemon = true, name = "stream-relay-request") { serve(client) }
                }
            } catch (_: IOException) {
                // Closed.
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        try {
            // Engines send a GET or HEAD without a body: the request line, then headers up to a blank line.
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val method = input.readLine()?.substringBefore(' ') ?: return
            val range = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .firstOrNull { it.startsWith("range:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            open(range).use { response -> relay(response, client.getOutputStream(), body = method != "HEAD") }
        } catch (_: IOException) {
            // The engine went away, or a newer request took the Provider stream.
        }
    }

    private fun open(range: String?): Response {
        val request = Request.Builder().url(upstream)
            // The engine gets the file's bytes as they are; OkHttp would otherwise unzip and change the length.
            .header("Accept-Encoding", "identity")
            .apply { if (range != null) header("Range", range) }
            .build()
        val call = http.newCall(request)
        val replaced = synchronized(lock) {
            val earlier = current
            earlier?.cancel()
            current = call
            earlier != null
        }
        // The Provider counts the stream just closed as open for a moment, and would refuse this one.
        if (replaced) Thread.sleep(PROVIDER_RELEASE_GRACE.inWholeMilliseconds)
        return call.execute()
    }

    private fun relay(response: Response, out: OutputStream, body: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ${response.code} ${response.message.ifEmpty { "OK" }}\r\n")
            for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified")) {
                response.header(name)?.let { append("$name: $it\r\n") }
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body) response.body?.byteStream()?.let { file ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var answered = false
            while (true) {
                val read = file.read(buffer)
                if (read < 0) break
                if (!answered) {
                    answered = true
                    answeredAt = TimeSource.Monotonic.markNow()
                }
                out.write(buffer, 0, read)
            }
        }
        out.flush()
    }

    override fun close() {
        server.close()
        synchronized(lock) { current?.cancel() }
    }
}
