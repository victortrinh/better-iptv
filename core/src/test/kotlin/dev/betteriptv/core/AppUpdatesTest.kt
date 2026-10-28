package dev.betteriptv.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** The in-app updater against a fake release host and a fake installer: check, the first-update permission, download and install. */
class AppUpdatesTest {
    @get:Rule val folder = TemporaryFolder()

    /** The release host: serves [files] by path, and redirects `/latest/…` to `/build-95/…` as GitHub does. */
    private val files = HashMap<String, ByteArray>()
    private val requested = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requested += path
            if (path.startsWith("/latest/")) {
                exchange.responseHeaders.add("Location", path.replace("/latest/", "/build-95/"))
                exchange.sendResponseHeaders(302, -1)
            } else {
                val body = files[path]
                if (body == null) exchange.sendResponseHeaders(404, -1)
                else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
            exchange.close()
        }
        start()
    }
    private val host = "http://127.0.0.1:${server.address.port}"

    private val installer = object : ApkInstaller {
        var allowed = true
        val installed = mutableListOf<ByteArray>()
        override fun canInstall() = allowed
        override fun install(apk: File) {
            installed += apk.readBytes()
        }
    }

    private val downloads by lazy { folder.root.resolve("updates") }

    private fun updates(installedVersionCode: Long = 91) = AppUpdates("$host/latest/update.json", installedVersionCode, downloads, installer)

    private val apk = ByteArray(300_000) { (it * 31).toByte() }

    private fun publish(versionCode: Long = 95, body: ByteArray = apk, sha256: String = sha256(body), size: Long = body.size.toLong()) {
        files["/build-95/better-iptv-0.1.$versionCode.apk"] = body
        files["/build-95/update.json"] = """
            {"versionCode": $versionCode, "versionName": "0.1.$versionCode", "apk": "better-iptv-0.1.$versionCode.apk", "sha256": "$sha256", "size": $size}
        """.trimIndent().toByteArray()
    }

    @After fun tearDown() = server.stop(0)

    @Test fun `a newer build is offered, with its APK beside the manifest it was redirected to`() = runBlocking {
        publish()
        val updates = updates()
        updates.check()
        val state = updates.state.value as UpdateState.Available
        assertEquals(95L, state.update.versionCode)
        assertEquals("0.1.95", state.update.versionName)
        assertEquals("$host/build-95/better-iptv-0.1.95.apk", state.update.apkUrl)
        // Checking downloads nothing.
        assertEquals(listOf("/latest/update.json", "/build-95/update.json"), requested)
    }

    @Test fun `the same or an older build is not offered`() = runBlocking {
        publish(versionCode = 91)
        val updates = updates(installedVersionCode = 91)
        updates.check()
        assertEquals(UpdateState.UpToDate, updates.state.value)
    }

    @Test fun `an unreachable or broken manifest offers nothing`() = runBlocking {
        val updates = updates()
        updates.check()
        assertEquals(UpdateState.Unknown, updates.state.value)
        files["/build-95/update.json"] = "not json".toByteArray()
        updates.check()
        assertEquals(UpdateState.Unknown, updates.state.value)
    }

    @Test fun `confirming downloads the build, checks it and opens the installer`() = runBlocking {
        publish()
        val updates = updates()
        updates.check()
        updates.update()
        assertEquals(95L, (updates.state.value as UpdateState.Installing).update.versionCode)
        assertArrayEquals(apk, installer.installed.single())
    }

    @Test fun `the first update asks for the install permission, then carries on once it is allowed`() = runBlocking {
        publish()
        installer.allowed = false
        val updates = updates()
        updates.check()
        updates.update()
        assertTrue(updates.state.value is UpdateState.NeedsPermission)
        assertTrue(installer.installed.isEmpty())
        assertTrue("/build-95/better-iptv-0.1.95.apk" !in requested)

        installer.allowed = true
        updates.update()
        assertTrue(updates.state.value is UpdateState.Installing)
        assertArrayEquals(apk, installer.installed.single())
    }

    @Test fun `a download that doesn't match the manifest's SHA-256 is never installed`() = runBlocking {
        publish(sha256 = "0".repeat(64))
        val updates = updates()
        updates.check()
        updates.update()
        val failed = updates.state.value as UpdateState.Failed
        assertTrue(failed.problem, "SHA-256" in failed.problem)
        assertTrue(installer.installed.isEmpty())
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `a cut-off download is never installed`() = runBlocking {
        publish(size = apk.size + 1L)
        val updates = updates()
        updates.check()
        updates.update()
        assertTrue(updates.state.value is UpdateState.Failed)
        assertTrue(installer.installed.isEmpty())
    }

    @Test fun `a finished download is reused when the installer is opened again, and dropped once installed`() = runBlocking {
        publish()
        updates().apply {
            check()
            update()
        }
        val downloadsBefore = requested.count { it.endsWith(".apk") }
        // The Viewer backed out of the installer and comes back: no second download.
        updates().apply {
            check()
            update()
        }
        assertEquals(downloadsBefore, requested.count { it.endsWith(".apk") })
        assertEquals(2, installer.installed.size)

        // Next launch, running the new build: the old download is cleaned up.
        updates(installedVersionCode = 95).check()
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
