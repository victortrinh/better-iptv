package dev.betteriptv.core

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reads the app's real update manifest, where GitHub serves it behind two redirects, and the first
 * bytes of the build it names: the self-updating app is stranded on a build whose update check can't
 * do this. Nothing is installed and the build isn't downloaded whole.
 * Skipped unless run with `./gradlew :core:jvmTest --tests '*RealUpdatesTest' -PrealUpdates`.
 */
class RealUpdatesTest {
    @get:Rule val folder = TemporaryFolder()

    private val installer = object : ApkInstaller {
        override fun canInstall() = false
        override fun install(apk: File, dexMetadata: File?) = error("Nothing is installed here.")
    }

    @Test fun `the update check reads GitHub's manifest and the build it names starts to download`() = runBlocking {
        assumeTrue("run with -PrealUpdates", System.getProperty("realUpdates") != null)
        val updates = AppUpdates(MANIFEST, installedVersionCode = 0, folder.root.resolve("updates"), installer)

        updates.check()

        val update = (updates.state.value as UpdateState.Available).update
        println("The manifest offers ${update.versionName} (${update.versionCode}), ${update.size} bytes, at ${update.apkUrl}; dex metadata at ${update.dexMetadata?.url}")
        assertTrue(update.apkUrl, update.apkUrl.startsWith(RELEASES) && update.apkUrl.endsWith(".apk"))
        assertTrue(update.versionCode > 0)
        // The build's start, through the client the updater downloads with: GitHub redirects this too.
        for ((url, size, magic) in listOfNotNull(Triple(update.apkUrl, update.size, "PK"), update.dexMetadata?.let { Triple(it.url, it.size, "PK") })) {
            coreHttp(OkHttpClient()).prepareGet(url) { header("Range", "bytes=0-1023") }.execute { response ->
                val bytes = ByteArray(1024)
                var read = 0
                val body = response.bodyAsChannel()
                while (read < bytes.size) read += body.readAvailable(bytes, read, bytes.size - read).also { if (it < 0) return@execute }
                println("${url.substringAfterLast('/')}: HTTP ${response.status.value}, ${response.headers["Content-Range"]}")
                assertEquals(206, response.status.value)
                assertEquals("bytes 0-1023/$size", response.headers["Content-Range"])
                assertEquals(magic, String(bytes, 0, 2, Charsets.ISO_8859_1))
            }
        }
    }

    private companion object {
        const val RELEASES = "https://github.com/victortrinh/better-iptv/releases/download/"
        const val MANIFEST = "https://github.com/victortrinh/better-iptv/releases/latest/download/update.json"
    }
}
