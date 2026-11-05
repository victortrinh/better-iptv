package dev.betteriptv.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * The Intent that opens YouTube video [videoId] in the device's YouTube app (ADR 0012), or null when
 * no YouTube app can take it, and the Title page then leaves its Trailer row out. A known YouTube
 * app is asked first, Fire TV's before Google's, with the video's web link; else any app that takes
 * YouTube's own `vnd.youtube:` link, which only YouTube apps declare. A browser that would take the
 * web link doesn't count: the trailer opens in YouTube or not at all.
 */
internal fun trailerIntent(packageManager: PackageManager, videoId: String): Intent? {
    val link = Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(videoId)}")
    return YOUTUBE_APPS.asSequence()
        .map { Intent(Intent.ACTION_VIEW, link).setPackage(it) }
        .plus(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${Uri.encode(videoId)}")))
        .firstOrNull { packageManager.resolveActivity(it, 0) != null }
}

/** Opens [intent] (a [trailerIntent]); Back from YouTube comes back to the Title page. */
internal fun Context.openTrailer(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Uninstalled since the page opened: the row is left out the next time it's shown.
        Log.w("Trailers", "No app took the trailer", e)
    }
}

/**
 * YouTube's apps, the Fire TV Stick's first; each is also in the manifest's `<queries>`, which
 * Android 11 and later need to see them.
 */
internal val YOUTUBE_APPS = listOf(
    "com.amazon.firetv.youtube",
    "com.google.android.youtube.tv",
    "com.google.android.youtube.googletv",
    "com.google.android.youtube",
)
