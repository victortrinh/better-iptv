package dev.betteriptv.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Motion
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * The Intent that opens YouTube video [videoId] in the device's YouTube app, for a trailer YouTube won't
 * let Zugig embed (ADR 0012), or null when no YouTube app can take it. A known YouTube
 * app is asked first, Fire TV's before Google's, with the video's web link; else any app that takes
 * YouTube's own `vnd.youtube:` link, which only YouTube apps declare. A browser that would take the
 * web link doesn't count: the trailer opens in YouTube or not at all. Back from YouTube lands on
 * YouTube's own home, not on Zugig (#385), which is why this is only the fallback.
 */
internal fun trailerIntent(packageManager: PackageManager, videoId: String): Intent? {
    val link = Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(videoId)}")
    return YOUTUBE_APPS.asSequence()
        .map { Intent(Intent.ACTION_VIEW, link).setPackage(it) }
        .plus(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${Uri.encode(videoId)}")))
        .firstOrNull { packageManager.resolveActivity(it, 0) != null }
}

/** Opens [intent] (a [trailerIntent]). */
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

/**
 * The Title page's Trailer screen ([TitleSection.Trailer], ADR 0012): YouTube video [videoId] in
 * YouTube's IFrame player, inside Zugig, so Back returns to the page with focus on Trailer. Until the
 * player shows its first frame, the Title's [art] and [name] cover it, as when a Version starts
 * ([StartingCover]), and the player fades in over them. OK (or Play/Pause) pauses and resumes, with
 * a pause sign while paused; Left and Right (or Rewind and Fast-forward) go back and forward 10 s.
 * Leaving the app pauses it. When the trailer ends, the page comes back ([onClose]).
 *
 * A trailer YouTube won't let others embed (player errors 101, 150, 153) opens in the device's YouTube
 * app instead ([trailerIntent]), and the page is already back under it. Any other error, or no YouTube
 * app, says the trailer can't play here; Back returns.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TrailerContent(videoId: String, name: String, art: StartingArt.Title, onClose: () -> Unit) {
    var pictured by remember(videoId) { mutableStateOf(false) }
    var failed by remember(videoId) { mutableStateOf(false) }
    val close by rememberUpdatedState(onClose)
    val context = LocalContext.current
    val host = LocalView.current
    val language = LocalConfiguration.current.locales[0].language
    val web = remember(videoId) {
        val main = Handler(Looper.getMainLooper())
        TrailerWebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            // Hidden until the trailer plays, over the cover; then it fades in.
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // It starts by itself, as OK on Trailer asked for it.
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            // The player's page calls these from its own thread.
            addJavascriptInterface(
                TrailerEvents(
                    onState = { state ->
                        main.post {
                            when (state) {
                                TrailerEvents.PLAYING -> pictured = true
                                TrailerEvents.ENDED -> close()
                            }
                        }
                    },
                    onError = { code ->
                        main.post {
                            val app = if (code in UNEMBEDDABLE) trailerIntent(context.packageManager, videoId) else null
                            if (app != null) {
                                context.openTrailer(app)
                                close()
                            } else {
                                failed = true
                            }
                        }
                    },
                ),
                TRAILER_BRIDGE,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            val origin = "https://${context.packageName}"
            loadDataWithBaseURL("$origin/", trailerPage(videoId, language, origin), "text/html", "utf-8", null)
        }
    }
    // Over the app's Compose content rather than in it: inside Compose, Fire OS 7's WebView plays the
    // sound but draws no picture (#385). It takes focus, so the remote's keys go to the player, and
    // Back still reaches the app's Back handling.
    DisposableEffect(web) {
        val content = host.rootView.findViewById<ViewGroup>(android.R.id.content)
        content.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.requestFocus()
        onDispose {
            content.removeView(web)
            web.destroy()
        }
    }
    LaunchedEffect(web, pictured, failed) {
        web.animate().alpha(if (pictured && !failed) 1f else 0f).setDuration(Motion.screenFadeMillis.toLong())
    }
    // Leaving the app pauses the trailer; it stays paused until OK.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> web.run { evaluateJavascript("pause()", null); onPause() }
                Lifecycle.Event.ON_RESUME -> web.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        // Under the player, which fades in over it once it plays, and out again if it fails.
        StartingCover(art, name, pictured = false)
        if (failed) {
            Text(
                stringResource(R.string.trailer_unavailable),
                style = Type.body,
                color = Colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.screenY + Spacing.xl),
            )
        }
    }
}

/**
 * The Trailer screen's WebView, which hands the remote's keys to the player's page: OK and Play/Pause
 * to `toggle()`, Left/Right and Rewind/Fast-forward to `seek()`. They're taken here, before the WebView,
 * which would otherwise eat them. Back never reaches the WebView: on the stick, a Back it saw closed
 * the Trailer screen and the Title page at once (#385). The app's own Back handling closes the screen.
 */
private class TrailerWebView(context: android.content.Context) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val script = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> return false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle()"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "seek(-$SEEK_SECONDS)"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek($SEEK_SECONDS)"
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) evaluateJavascript(script, null)
        return true
    }
}

/** YouTube's IFrame player errors for a video its owner won't let others embed, or that needs the page's origin. */
private val UNEMBEDDABLE = setOf(101, 150, 153)

private const val SEEK_SECONDS = 10

/** The name the trailer's page calls [TrailerEvents] by. */
internal const val TRAILER_BRIDGE = "Zugig"

/** What YouTube's IFrame player tells the Trailer screen: its [state] changes and its [error]s, by YouTube's codes. */
internal class TrailerEvents(private val onState: (Int) -> Unit, private val onError: (Int) -> Unit) {
    @JavascriptInterface fun state(code: Int) = onState(code)

    @JavascriptInterface fun error(code: Int) = onError(code)

    companion object {
        const val ENDED = 0
        const val PLAYING = 1
    }
}

/**
 * The page the Trailer screen loads at [origin]: YouTube's IFrame player for [videoId], filling the
 * screen, starting by itself, in [language], with related videos from the trailer's own channel only.
 * Its keyboard is off, so the remote's keys reach it once, through `toggle()`, `seek()` and `pause()`.
 * Paused that way, YouTube's player shows nothing, so the page shows a pause sign in the middle.
 * Only a YouTube id's characters are kept, so nothing TMDB sends can break out of the script.
 */
internal fun trailerPage(videoId: String, language: String, origin: String): String {
    fun plain(text: String) = text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    return """<!doctype html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html, body, #player { margin: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
#paused { display: none; position: fixed; left: 50%; top: 50%; width: 96px; height: 96px; margin: -48px 0 0 -48px; border-radius: 50%; background: rgba(7, 8, 10, 0.6); pointer-events: none; }
#paused::before, #paused::after { content: ""; position: absolute; top: 30px; width: 10px; height: 36px; border-radius: 2px; background: #fff; }
#paused::before { left: 34px; } #paused::after { right: 34px; }
</style>
</head><body><div id="player"></div><div id="paused"></div>
<script>
var player;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    width: '100%', height: '100%', videoId: "${plain(videoId)}",
    playerVars: { autoplay: 1, controls: 1, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, hl: "${plain(language)}", origin: "${origin.filter { it != '"' && it != '<' }}" },
    events: {
      onReady: function (e) { e.target.playVideo(); },
      onStateChange: function (e) { document.getElementById('paused').style.display = e.data == 2 ? 'block' : 'none'; $TRAILER_BRIDGE.state(e.data); },
      onError: function (e) { $TRAILER_BRIDGE.error(e.data); }
    }
  });
}
function ready() { return player && player.getPlayerState; }
function toggle() { if (ready()) { if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo(); } }
function pause() { if (ready()) player.pauseVideo(); }
function seek(s) { if (ready()) player.seekTo(Math.max(0, player.getCurrentTime() + s), true); }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>"""
}
