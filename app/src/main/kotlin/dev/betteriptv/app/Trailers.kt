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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.withFrameNanos
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
 * YouTube's IFrame player, inside Zugig, so Back returns to the page with focus on Trailer. OK on
 * Trailer shows the Title's [art] and [name] at once, as when a Version starts ([StartingCover]). The
 * player is built behind that cover, once it's on screen: the first WebView of a session holds the
 * main thread for seconds (#399). The cover stays over the player until its frames are on screen, then fades away to
 * the picture. OK (or Play/Pause) pauses and resumes, with a pause sign while paused; Left and Right
 * (or Rewind and Fast-forward) go back and forward 10 s. Leaving the app pauses it. Back covers and mutes the
 * trailer at once and the page comes back ([onClose]), as it does when the trailer ends.
 *
 * A trailer YouTube won't let others embed (player errors 101, 150, 153) opens in the device's YouTube
 * app instead ([trailerIntent]), and the page is already back under it. Any other error, or no YouTube
 * app, says the trailer can't play here; Back returns.
 */
@Composable
internal fun TrailerContent(videoId: String, name: String, art: StartingArt.Title, onClose: () -> Unit) {
    val pictured = remember(videoId) { mutableStateOf(false) }
    val failed = remember(videoId) { mutableStateOf(false) }
    val close by rememberUpdatedState(onClose)
    val context = LocalContext.current
    val host = LocalView.current
    val language = LocalConfiguration.current.locales[0].language
    val parent = rememberCompositionContext()
    var web by remember(videoId) { mutableStateOf<WebView?>(null) }
    // Back was pressed: the cover is back over the player at once, before the app closes the screen on
    // Back's release, so nothing of YouTube's shows as the WebView goes (#407).
    val closing = remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        // The cover fades in first; building the WebView can hold the main thread, so it waits behind a still cover.
        val shown = withFrameNanos { it }
        while (withFrameNanos { it } - shown < COVER_SETTLE_MILLIS * 1_000_000L) continue
        web = trailerWebView(
            context, videoId, language,
            onPictured = { pictured.value = true },
            onBack = {
                closing.value = true
                web?.evaluateJavascript("mute()", null)
            },
            onEnded = { close() },
            onError = { code ->
                val app = if (code in UNEMBEDDABLE) trailerIntent(context.packageManager, videoId) else null
                if (app != null) {
                    context.openTrailer(app)
                    close()
                } else {
                    failed.value = true
                }
            },
        )
    }
    // The cover over the player fades once the trailer's frames are on screen and the main thread draws steadily again: starting
    // the picture can hold it for half a second, and a fade run through that is a cut (#399).
    val faded = remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(pictured.value) {
        if (!pictured.value) return@LaunchedEffect
        awaitSteadyFrames()
        faded.value = true
    }
    // Over the app's Compose content rather than in it: inside Compose, Fire OS 7's WebView plays the
    // sound but draws no picture (#385). It takes focus, so the remote's keys go to the player, and
    // Back still reaches the app's Back handling. Over it, a cover of its own fades away once it plays:
    // the WebView's own fade cut straight to the picture on the stick (#399).
    DisposableEffect(web) {
        val player = web ?: return@DisposableEffect onDispose { }
        val content = host.rootView.findViewById<ViewGroup>(android.R.id.content)
        val cover = ComposeView(context).apply {
            setParentCompositionContext(parent)
            setContent {
                // Closing starts a fresh cover, at full strength from its first frame.
                val shut = closing.value
                key(shut) { TrailerCover(art, name, faded.value && !shut, failed.value) }
            }
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val fill = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(player, fill)
        content.addView(cover, fill)
        player.requestFocus()
        onDispose {
            // Muted before it goes, so the sound stops with the picture. Not stopped: a stopped player is
            // black, and it showed until the page's first frame was ready.
            player.evaluateJavascript("mute()", null)
            content.removeView(cover)
            cover.disposeComposition()
            content.removeView(player)
            // Destroying it can hold the main thread too: once the page is back.
            Handler(Looper.getMainLooper()).postDelayed({ player.destroy() }, DESTROY_DELAY_MILLIS)
        }
    }
    // Leaving the app pauses the trailer; it stays paused until OK.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val player = web ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.run { evaluateJavascript("pause()", null); onPause() }
                Lifecycle.Event.ON_RESUME -> player.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Under the player: what shows before it's built. The cover over it is the same.
    TrailerCover(art, name, pictured = false, failed = failed.value)
}

/** The Trailer screen's cover: the Title's [art] and [name] until the player has [pictured] the trailer, and why it [failed] if it did. */
@Composable
private fun TrailerCover(art: StartingArt.Title, name: String, pictured: Boolean, failed: Boolean) {
    Box(Modifier.fillMaxSize()) {
        StartingCover(art, name, pictured = pictured && !failed)
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

/** The WebView that plays [videoId] in YouTube's IFrame player, its page already loading; the player's events come on the main thread. */
@SuppressLint("SetJavaScriptEnabled")
private fun trailerWebView(
    context: Context,
    videoId: String,
    language: String,
    onPictured: () -> Unit,
    onEnded: () -> Unit,
    onError: (Int) -> Unit,
    onBack: () -> Unit,
): WebView = TrailerWebView(context, onBack).apply {
    val main = Handler(Looper.getMainLooper())
    setBackgroundColor(android.graphics.Color.BLACK)
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
                if (state == TrailerEvents.ENDED) main.post { onEnded() }
            },
            onError = { code -> main.post { onError(code) } },
            onPictured = { main.post { onPictured() } },
        ),
        TRAILER_BRIDGE,
    )
    isFocusable = true
    isFocusableInTouchMode = true
    val origin = "https://${context.packageName}"
    loadDataWithBaseURL("$origin/", trailerPage(videoId, language, origin), "text/html", "utf-8", null)
}

/**
 * Waits until the main thread draws [STEADY_FRAMES] frames in a row, each within [STEADY_GAP_MILLIS]
 * of the one before, or [STEADY_WAIT_MILLIS] have passed.
 */
internal suspend fun awaitSteadyFrames() {
    val start = withFrameNanos { it }
    var last = start
    var steady = 0
    while (steady < STEADY_FRAMES && last - start < STEADY_WAIT_MILLIS * 1_000_000L) {
        val now = withFrameNanos { it }
        steady = if (now - last <= STEADY_GAP_MILLIS * 1_000_000L) steady + 1 else 0
        last = now
    }
}

private const val STEADY_FRAMES = 3
private const val STEADY_GAP_MILLIS = 50L
private const val STEADY_WAIT_MILLIS = 1_500L

/** How long the cover is on screen before the player is built behind it: its fade in, and its art's from disk. */
private const val COVER_SETTLE_MILLIS = 400L

/** How long after the Trailer screen goes its WebView is destroyed: after the page has faded back in. */
private const val DESTROY_DELAY_MILLIS = 1_000L

/**
 * The Trailer screen's WebView, which hands the remote's keys to the player's page: OK and Play/Pause
 * to `toggle()`, Left/Right and Rewind/Fast-forward to `seek()`. They're taken here, before the WebView,
 * which would otherwise eat them. Back never reaches the WebView: on the stick, a Back it saw closed
 * the Trailer screen and the Title page at once (#385). The app's own Back handling closes the screen,
 * on Back's release; its press tells the screen first ([onBack]), so the cover is back over the player
 * by then.
 */
private class TrailerWebView(context: android.content.Context, private val onBack: () -> Unit) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val script = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onBack()
                return false
            }
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

/** How far the trailer's time moves on after YouTube says it plays before its frames count as on screen. */
private const val PICTURED_SECONDS = 0.2

/** The name the trailer's page calls [TrailerEvents] by. */
internal const val TRAILER_BRIDGE = "Zugig"

/**
 * What YouTube's IFrame player tells the Trailer screen: its [state] changes and its [error]s, by
 * YouTube's codes, and when the trailer is [pictured]: its time has moved on since it said it plays.
 * YouTube says it plays before the stick draws its first frame (#407).
 */
internal class TrailerEvents(private val onState: (Int) -> Unit, private val onError: (Int) -> Unit, private val onPictured: () -> Unit = {}) {
    @JavascriptInterface fun state(code: Int) = onState(code)

    @JavascriptInterface fun error(code: Int) = onError(code)

    @JavascriptInterface fun pictured() = onPictured()

    companion object {
        const val ENDED = 0
        const val PLAYING = 1
    }
}

/**
 * The page the Trailer screen loads at [origin]: YouTube's IFrame player for [videoId], filling the
 * screen, starting by itself, in [language], with related videos from the trailer's own channel only.
 * YouTube's controls are off (#399). Its keyboard is off too, so the remote's keys reach it once,
 * through `toggle()`, `seek()`, `pause()` and `mute()`.
 * Paused that way, YouTube's player shows nothing, so the page shows a pause sign in the middle.
 * Once it plays, it says it's pictured when the trailer's time has moved on [PICTURED_SECONDS]: then
 * its frames are being drawn, where "playing" can come before the first (#407).
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
    playerVars: { autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, hl: "${plain(language)}", origin: "${origin.filter { it != '"' && it != '<' }}" },
    events: {
      onReady: function (e) { e.target.playVideo(); },
      onStateChange: function (e) { document.getElementById('paused').style.display = e.data == 2 ? 'block' : 'none'; $TRAILER_BRIDGE.state(e.data); if (e.data == 1) watch(); },
      onError: function (e) { $TRAILER_BRIDGE.error(e.data); }
    }
  });
}
var watching = false;
function watch() {
  if (watching) return;
  watching = true;
  var from = player.getCurrentTime();
  var tick = setInterval(function () {
    if (player.getCurrentTime() - from >= $PICTURED_SECONDS) { clearInterval(tick); $TRAILER_BRIDGE.pictured(); }
  }, 50);
}
function ready() { return player && player.getPlayerState; }
function toggle() { if (ready()) { if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo(); } }
function pause() { if (ready()) player.pauseVideo(); }
function mute() { if (ready()) player.mute(); }
function seek(s) { if (ready()) player.seekTo(Math.max(0, player.getCurrentTime() + s), true); }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>"""
}
