package dev.betteriptv.core

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A build newer than the installed one, as the update manifest CI publishes beside it describes it
 * (`update.json`, see .github/workflows/release.yml): `{"versionCode": 95, "versionName": "0.1.95",
 * "apk": "better-iptv-0.1.95.apk", "sha256": "…", "size": 61234567}`. [apkUrl] is resolved against
 * the manifest's own address, so a relative `apk` names a file beside it.
 */
data class AppUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val size: Long)

/** Where keeping the app up to date stands. */
sealed interface UpdateState {
    /** Not checked yet, or the manifest couldn't be read: nothing is offered. */
    data object Unknown : UpdateState

    /** The installed build is the newest. */
    data object UpToDate : UpdateState

    /** [update] is newer than the installed build; nothing happens until the Viewer asks for it. */
    data class Available(val update: AppUpdate) : UpdateState

    /** The Viewer asked for [update], but Android doesn't let this app install apps yet: the first update guides them there. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState

    /** [downloaded] of [update]'s bytes are here. */
    data class Downloading(val update: AppUpdate, val downloaded: Long) : UpdateState

    /** Android's installer was opened on [update]; installing replaces the running app. */
    data class Installing(val update: AppUpdate) : UpdateState

    /** Downloading [update] or opening the installer failed, for [problem]. Asking again starts over. */
    data class Failed(val update: AppUpdate, val problem: String) : UpdateState
}

/** The build on offer, whatever step updating to it is at; null when there is none. */
val UpdateState.offered: AppUpdate?
    get() = when (this) {
        is UpdateState.Available -> update
        is UpdateState.NeedsPermission -> update
        is UpdateState.Downloading -> update
        is UpdateState.Installing -> update
        is UpdateState.Failed -> update
        UpdateState.Unknown, UpdateState.UpToDate -> null
    }

/** Hands a downloaded APK to the system. The app's is Android's package installer. */
interface ApkInstaller {
    /** Whether Android lets this app install apps ("install unknown apps" is allowed for it). */
    fun canInstall(): Boolean

    /** Opens the installer on [apk], which asks the Viewer to confirm. */
    fun install(apk: File)
}

/**
 * Keeps the sideloaded app up to date (ADR 0006): [check] reads the update manifest at
 * [manifestUrl] and offers a newer build than [installedVersionCode]; [update], once the Viewer
 * confirms, downloads it into [downloads], checks its size and SHA-256, and hands it to [installer].
 * Every step shows in [state].
 */
class AppUpdates(
    private val manifestUrl: String,
    private val installedVersionCode: Long,
    private val downloads: File,
    private val installer: ApkInstaller,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** One check or update at a time, so a second press doesn't download twice. */
    private val busy = Mutex()

    /**
     * Reads the manifest and offers its build if it is newer. When the manifest can't be read, a
     * build already offered stays offered and nothing else is. Leftover downloads of other builds
     * are deleted.
     */
    suspend fun check() = busy.withLock {
        withContext(Dispatchers.IO) {
            val update = try {
                manifest()
            } catch (e: IOException) {
                null
            }
            when {
                update == null -> if (_state.value == UpdateState.UpToDate) _state.value = UpdateState.Unknown
                update.versionCode > installedVersionCode -> if (offered()?.versionCode != update.versionCode) _state.value = UpdateState.Available(update)
                else -> _state.value = UpdateState.UpToDate
            }
            val keep = offered()?.let(::apkFile)
            downloads.listFiles()?.filter { it != keep }?.forEach { it.delete() }
        }
    }

    /**
     * The Viewer confirmed the offered build: unless Android first needs them to allow this app to
     * install apps ([UpdateState.NeedsPermission], call again once they did), downloads it (or reuses
     * a finished download) and opens the installer.
     */
    suspend fun update() = busy.withLock {
        val update = offered() ?: return@withLock
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(update)
            return@withLock
        }
        try {
            val apk = withContext(Dispatchers.IO) { download(update) }
            installer.install(apk)
            _state.value = UpdateState.Installing(update)
        } catch (e: IOException) {
            _state.value = UpdateState.Failed(update, e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // The installer couldn't be opened (no activity for it, a missing file provider).
            _state.value = UpdateState.Failed(update, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun offered(): AppUpdate? = _state.value.offered

    private fun manifest(): AppUpdate? {
        http.newCall(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The update manifest answered HTTP ${response.code}.")
            val body = json.fromJson(response.body!!.string()) as? Map<*, *> ?: return null
            val versionCode = (body["versionCode"] as? Number)?.toLong() ?: return null
            val versionName = body["versionName"] as? String ?: return null
            val apk = body["apk"] as? String ?: return null
            val sha256 = (body["sha256"] as? String)?.lowercase()?.takeIf { it.matches(SHA256) } ?: return null
            val size = (body["size"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            // Against the address the manifest was finally served from, after GitHub's redirects.
            val apkUrl = response.request.url.resolve(apk)?.toString() ?: return null
            return AppUpdate(versionCode, versionName, apkUrl, sha256, size)
        }
    }

    /** [update]'s APK in [downloads], downloaded unless a whole, verified copy is already there. */
    private fun download(update: AppUpdate): File {
        val apk = apkFile(update)
        if (apk.length() == update.size && sha256(apk) == update.sha256) return apk
        downloads.mkdirs()
        val partial = File(downloads, "${apk.name}.part")
        _state.value = UpdateState.Downloading(update, 0)
        http.newCall(Request.Builder().url(update.apkUrl.toHttpUrl()).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download answered HTTP ${response.code}.")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            response.body!!.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastShown = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded > update.size) throw IOException("The download is larger than the manifest says.")
                        // Often enough for a smooth bar, not on every buffer.
                        if (downloaded - lastShown >= update.size / 200) {
                            lastShown = downloaded
                            _state.value = UpdateState.Downloading(update, downloaded)
                        }
                    }
                }
            }
            if (downloaded != update.size) throw IOException("The download stopped at $downloaded of ${update.size} bytes.")
            if (hex(digest.digest()) != update.sha256) {
                partial.delete()
                throw IOException("The download doesn't match the manifest's SHA-256.")
            }
        }
        if (!partial.renameTo(apk)) throw IOException("Couldn't keep the download.")
        return apk
    }

    private fun apkFile(update: AppUpdate) = File(downloads, "update-${update.versionCode}.apk")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        val json = Moshi.Builder().build().adapter(Any::class.java)
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
