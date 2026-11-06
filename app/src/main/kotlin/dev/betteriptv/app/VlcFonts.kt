package dev.betteriptv.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Builds VLC's font list ahead of its first picture (#410). The first time VLC makes its video output
 * after an install, fontconfig lists every font on the device before the picture can show: 41 s on the
 * stick. It keeps the list in a cache in VLC's home, and later starts take milliseconds. So when the
 * cache is missing, a silent VLC with nowhere to draw opens a blank image, which builds it (42 s on
 * the stick, 20 s after launch). A VLC that starts meanwhile waits for the same list, as it would have anyway.
 */
internal object VlcFonts {
    fun buildIfMissing(context: Context) {
        val app = context.applicationContext
        // The folder LibVLC makes its home.
        if (vlcFontsCached(app.getDir("vlc", Context.MODE_PRIVATE))) return
        thread(name = "vlc-fonts", priority = Thread.MIN_PRIORITY) {
            val started = System.nanoTime()
            try {
                build(app)
                Log.i(PLAYER_TAG, "VLC's fonts are listed, in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Exception) {
                Log.w(PLAYER_TAG, "VLC's fonts weren't listed ahead", e)
            }
        }
    }

    private fun build(context: Context) {
        val image = File(context.cacheDir, "vlc-fonts.png")
        image.outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val vlc = LibVLC(context, arrayListOf("--vout=dummy", "--no-audio"))
        val player = MediaPlayer(vlc)
        val done = CountDownLatch(1)
        try {
            // VLC makes its video output once the font list is built. With nowhere to draw, making
            // it fails and the image ends: either way the list is built by then.
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Vout, MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError -> done.countDown()
                }
            }
            val media = Media(vlc, Uri.fromFile(image))
            media.setHWDecoderEnabled(false, false)
            player.media = media
            media.release()
            player.play()
            done.await(BUILD_WAIT_MINUTES, TimeUnit.MINUTES)
            player.setEventListener(null)
            player.stop()
        } finally {
            player.release()
            vlc.release()
            image.delete()
        }
    }

    private const val BUILD_WAIT_MINUTES = 3L
}

/** Whether fontconfig's cache of the font list is in VLC's [home] (LibVLC's `app_vlc`). */
internal fun vlcFontsCached(home: File): Boolean =
    home.resolve(".cache/fontconfig").list()?.any { it.contains(".cache-") } == true
