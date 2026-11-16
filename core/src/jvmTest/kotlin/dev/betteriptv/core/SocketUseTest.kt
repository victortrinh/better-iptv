package dev.betteriptv.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * No shared Core test opens a socket (#580): a test file that names a socket, a local server, OkHttp or
 * Android's relay says so with the [RealSockets] rule, which is also what lets it past [NoSockets] when
 * it runs. Those are the tests that stay on the JVM when the rest run on Apple and JavaScript.
 */
class SocketUseTest {
    private val sources = File(System.getProperty("user.dir")).resolve("src/jvmTest/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    /** A file's code, without its comments: they may speak of sockets freely. */
    private fun code(file: File) = file.readLines()
        .filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") } }
        .joinToString("\n") { it.substringBefore(" // ") }

    @Test fun `only the tests marked as needing real sockets name one`() {
        assertTrue("no test sources under ${System.getProperty("user.dir")}", sources.size > 50)

        val unmarked = sources.filter { it.name !in TRANSPORT }.filter { file ->
            val code = code(file)
            SOCKETS.containsMatchIn(code) && !MARKED.containsMatchIn(code)
        }

        assertEquals(emptyList<String>(), unmarked.map { "${it.name}: ${SOCKETS.find(code(it))!!.value}" })
    }

    @Test fun `a test that isn't marked is refused a socket, to listen on or to connect`() {
        assumeTrue("this JVM has no security manager to refuse with", NoSockets.guards)
        // Reaching the fakes is what arms the guard, as in any test that has a Provider or a Cloud.
        FakeProvider().close()
        try {
            assertThrows(SecurityException::class.java) { ServerSocket(0).close() }
            assertThrows(SecurityException::class.java) { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).stop(0) }
            assertThrows(SecurityException::class.java) { Socket("127.0.0.1", 9).close() }
            // A refusal that the code under test swallowed still fails the test, when its fake closes.
            assertThrows(AssertionError::class.java) { FakeProvider().close() }
        } finally {
            NoSockets.forget()
        }
    }

    private companion object {
        /** The socket transport itself, the guard, and this test's own patterns. */
        val TRANSPORT = setOf("SocketHosts.kt", "NoSockets.kt", "SocketUseTest.kt")

        val SOCKETS = Regex(
            """java\.net\.(?!URI\b|URLEncoder\b|URLDecoder\b)|java\.nio\.channels|com\.sun\.net|\bServerSocket\b|\bSocket\(|HttpURLConnection|\bHttpServer\b|openConnection\(|""" +
                """okhttp|OkHttp|\bcoreHttp\(|\bupdatesHttp\(|LocalStreamRelay|\bOverSockets\b|MockWebServer|ktor\.client\.engine\.(?!mock)""",
        )

        val MARKED = Regex("""\bRealSockets\(\)|: SocketTest\(\)""")
    }
}
