package dev.betteriptv.core

import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Android's network for the Core (#579): the Core's client on the app's OkHttp client. */
class OkHttpNetworkTest : SocketTest() {
    @Test fun `the Core's client asks as OkHttp does, on the pool of the client it is given`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val asked = CompletableDeferred<List<String>>()
            thread(isDaemon = true) {
                val socket = server.accept()
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                asked.complete(generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList())
                // A wrong login, as the surveyed Provider answers it; the connection stays open.
                socket.getOutputStream().apply { write("HTTP/1.1 513 Wrong\r\nContent-Length: 0\r\n\r\n".toByteArray()) }.flush()
            }
            val okHttp = OkHttpClient()

            val answer = coreHttp(okHttp).get("http://127.0.0.1:${server.localPort}/player_api.php?username=viewer")

            assertEquals(513, answer.status.value)
            val request = asked.await()
            assertEquals("GET /player_api.php?username=viewer HTTP/1.1", request.first())
            val headers = request.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            // What OkHttp sends by itself, and nothing of Ktor's: no `User-Agent: ktor-client`, no `Accept`.
            assertEquals(setOf("host", "connection", "accept-encoding", "user-agent"), headers.keys)
            assertEquals("gzip", headers["accept-encoding"])
            assertTrue(headers.getValue("user-agent"), headers.getValue("user-agent").startsWith("okhttp/"))
            // The connection is kept in the pool of the client the app gave, which Media3 reads with too.
            assertEquals(1, okHttp.connectionPool.connectionCount())
        }
    }

    @Test fun `a login is written into the Provider's address as OkHttp wrote it`() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val asked = CompletableDeferred<String>()
            thread(isDaemon = true) {
                val socket = server.accept()
                asked.complete(socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine())
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray()) }.flush()
                socket.close()
            }
            val login = ProviderLogin("http://127.0.0.1:${server.localPort}", "v i+e@r", "p&a=5/5 %x#é")

            XtreamClient(login, coreHttp()).movieCategories { _, _ -> }

            // What OkHttp's HttpUrl made of the same login before the Core had this client (#579): a space is %20, never a plus.
            assertEquals(
                "GET /player_api.php?username=v%20i%2Be%40r&password=p%26a%3D5%2F5%20%25x%23%C3%A9&action=get_vod_categories HTTP/1.1",
                runBlocking { asked.await() },
            )
            assertEquals("http://127.0.0.1:${server.localPort}/movie/v%20i+e@r/p&a=5%2F5%20%25x%23%C3%A9/7.mkv", XtreamClient(login, coreHttp()).movieLocation(7, "mkv"))
        }
    }
}
