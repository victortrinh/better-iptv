package dev.betteriptv.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.betteriptv.app.ui.theme.Motion
import dev.betteriptv.core.Library.Companion.MIN_BACKDROP_RATIO
import dev.betteriptv.core.Library.Companion.MIN_POSTER_WIDTH
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import android.graphics.BitmapFactory
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Precision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The artwork at [url] (the Provider's link, loaded as given), loaded in the background; null when there is
 * none or it couldn't be loaded, so the caller can show the Title's name instead. Only a failure
 * recomposes the caller: the painter redraws itself when the image arrives.
 *
 * It is decoded at the size it's drawn, not the file's. A [card]'s artwork waits until the card has
 * been on screen a moment ([SettleFirst]): holding Down through a grid brings a row of cards in at
 * every press, and decoding all their posters kept the stick's garbage collector running (#33).
 *
 * It arrives, it doesn't pop: an image read from disk or the network fades in over
 * [Motion.imageFadeMillis], and one already in the memory cache shows on its first frame, so going
 * back to a screen stays instant ([FadeIn]).
 */
@Composable
fun rememberArtwork(url: String?, card: Boolean = false): Painter? = rememberArtwork(url, card, onArrived = null)

/**
 * A Title's poster: the first of its [posters] (the order [dev.betteriptv.core.TitleSummary.posters]
 * gives) that isn't refused as it decodes, its file under [MIN_POSTER_WIDTH] wide ([PosterFloor],
 * #526), as [rememberArtwork] gives it. Null when there is none, every one is refused, or the one
 * tried couldn't be loaded, so the card shows the Title's name on glass. A poster refused once is
 * passed over at once on every card while the app runs.
 */
@Composable
fun rememberPoster(posters: List<String>, card: Boolean = false): Painter? {
    var refusedHere by remember(posters) { mutableIntStateOf(0) }
    val url = remember(posters, refusedHere) { posters.firstOrNull { it !in PosterFloor.refused } }
    return rememberArtwork(url, card, onArrived = null, poster = true, onRefused = {
        PosterFloor.refused.add(it)
        refusedHere++
    })
}

/**
 * [rememberArtwork], calling [onArrived] with the image's decoded width and height once it arrives.
 * A [poster] whose file is too narrow ([PosterFloor]) fails to load, and [onRefused] is called with it.
 */
@Composable
private fun rememberArtwork(
    url: String?,
    card: Boolean,
    onArrived: ((width: Int, height: Int) -> Unit)?,
    poster: Boolean = false,
    onRefused: (String) -> Unit = {},
): Painter? {
    if (url == null) return null
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val request = remember(url, card, poster) { artworkRequest(context, url, card, poster) }
    val image = rememberAsyncImagePainter(request, onError = {
        failed = true
        if (it.result.throwable is PosterFloor.TooNarrow) onRefused(url)
    })
    val fade = remember(url) { Fade(0f) }
    LaunchedEffect(image, fade) {
        // This url's image, not the one the painter showed before it.
        val arrived = image.state.first { it is AsyncImagePainter.State.Success && it.result.request.data == url } as AsyncImagePainter.State.Success
        onArrived?.invoke(arrived.result.image.width, arrived.result.image.height)
        // Over frames, however late they come, so art arriving as the main thread is held up doesn't pop ([Fade], #398).
        if (arrived.result.dataSource == DataSource.MEMORY_CACHE) fade.show() else fade.fadeIn(Motion.imageFadeMillis)
    }
    val painter = remember(image, fade) { FadeIn(image, fade) }
    return if (failed) null else painter
}

/**
 * The request for the artwork at [url]; a [card]'s waits to settle first ([SettleFirst]), a
 * [poster]'s is refused when its file is too narrow ([PosterFloor]). Its memory cache key is the url alone.
 */
private fun artworkRequest(context: PlatformContext, url: String, card: Boolean, poster: Boolean = false): ImageRequest =
    ImageRequest.Builder(context).data(url).precision(Precision.EXACT).apply {
        if (card) extras[SettleFirst.Key] = true
        if (poster) extras[PosterFloor.Key] = true
    }.build()

/**
 * Empties [loader]'s caches, on disk and in memory, so every artwork is loaded again from the link
 * it's given. Run once, on the first launch after the release that stopped holding the cloud's
 * metadata ([dev.betteriptv.core.Library.clearImagesOnce], #480): the curated art an older app
 * cached goes, and the Provider's links load again as they are. Blocking: call it off the main thread.
 */
fun clearCachedArtwork(loader: ImageLoader) {
    loader.diskCache?.clear()
    loader.memoryCache?.clear()
}

/**
 * Loads the artwork at [url] into the memory cache, as [rememberArtwork] asks for it (at the
 * original size its painter loads it at), so it shows on the first frame it's drawn, with no fade.
 * It goes on when the caller leaves: the featured hero's art, read for the next entry (#386).
 */
fun preloadArtwork(context: PlatformContext, url: String) {
    SingletonImageLoader.get(context).enqueue(preloadRequest(context, url))
}

/** [preloadArtwork], returning once the artwork is in the memory cache, or couldn't be loaded. */
suspend fun loadArtwork(context: PlatformContext, url: String) {
    SingletonImageLoader.get(context).execute(preloadRequest(context, url))
}

/**
 * The backdrops refused as they were decoded ([check]), and [onRefused], which remembers each on the
 * device ([dev.betteriptv.core.Library.refuseBackdrop]) so it counts as none on every screen from
 * then on, after the app starts again too. MainActivity provides the app's ([LocalBackdrops]).
 */
class Backdrops(private val onRefused: suspend (String) -> Unit = {}) {
    private val refused = ConcurrentHashMap.newKeySet<String>()

    /** Whether the backdrop at [url] was refused while the app ran. */
    fun refused(url: String) = url in refused

    /**
     * Whether an image decoded at [width] × [height] is a backdrop ([isBackdrop]). One that isn't (a
     * poster handed over as a backdrop) is refused, once.
     */
    suspend fun check(url: String, width: Int, height: Int): Boolean {
        if (isBackdrop(width, height)) return true
        if (refused.add(url)) onRefused(url)
        return false
    }
}

/**
 * Whether an image decoded at [width] × [height] is a backdrop: at least [MIN_BACKDROP_RATIO] times
 * as wide as tall. One with no size of its own (a drawable, not a picture) can't be judged and passes.
 */
internal fun isBackdrop(width: Int, height: Int) = width <= 0 || height <= 0 || width >= MIN_BACKDROP_RATIO * height

/** The app's [Backdrops]; one that remembers nothing past the screen by default (in tests). */
val LocalBackdrops = staticCompositionLocalOf { Backdrops() }

/**
 * The backdrop at [url], as [rememberArtwork] gives it (a [card]'s once settled), checked once decoded ([Backdrops.check]):
 * null when there is none, it couldn't be loaded, or it is a poster handed over as a backdrop, so
 * the screen takes the layout it has without one.
 */
@Composable
fun rememberBackdrop(url: String?, card: Boolean = false): Painter? {
    val backdrops = LocalBackdrops.current
    var refused by remember(url) { mutableStateOf(url != null && backdrops.refused(url)) }
    val scope = rememberCoroutineScope()
    val painter = rememberArtwork(url.takeUnless { refused }, card, onArrived = { width, height ->
        if (url != null && !isBackdrop(width, height)) {
            refused = true
            scope.launch { backdrops.check(url, width, height) }
        }
    })
    return painter.takeUnless { refused }
}

/**
 * Loads the backdrop at [url] into the memory cache, as [loadArtwork] does, and says whether it is
 * one ([Backdrops.check]); false too when it couldn't be loaded.
 */
suspend fun loadBackdrop(context: PlatformContext, url: String, backdrops: Backdrops): Boolean {
    if (backdrops.refused(url)) return false
    val image = SingletonImageLoader.get(context).execute(preloadRequest(context, url)).image ?: return false
    return backdrops.check(url, image.width, image.height)
}

private fun preloadRequest(context: PlatformContext, url: String) =
    artworkRequest(context, url, card = false).newBuilder().size(coil3.size.Size.ORIGINAL).build()

/** How long a card is on screen, or focus rests on one, before artwork loads for it ([SettleFirst], the rows' preloading). */
const val SETTLE_MILLIS = 250L

/**
 * The artwork at [url] as it already is in the memory cache, at whatever size it was decoded for
 * there, on this very frame; null when it isn't there (or [url] is null). Nothing is loaded: a
 * screen opened from a card draws the card's own copy of a picture while the larger one it asks
 * [rememberArtwork] for arrives, so its first frame is that picture, not bare ground.
 */
@Composable
fun rememberArtworkInMemory(url: String?): Painter? {
    if (url == null) return null
    val context = LocalPlatformContext.current
    return remember(url) {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return@remember null
        cache.keys.firstOrNull { it.key == url }?.let { cache[it]?.image?.asPainter(context) }
    }
}

/**
 * Draws [image] at the [fade]'s alpha, or at full alpha at once when it came from the memory cache.
 * Only the drawing reads the fade, so an image fading in only redraws itself and recomposes nothing.
 */
private class FadeIn(private val image: AsyncImagePainter, private val fade: Fade) : Painter() {
    private var alpha = DefaultAlpha
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size get() = image.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val fromMemory = (image.state.value as? AsyncImagePainter.State.Success)?.result?.dataSource == DataSource.MEMORY_CACHE
        with(image) { draw(size, alpha * if (fromMemory) 1f else fade.value, colorFilter) }
    }
}

/**
 * Holds back an image marked in its request until it has been wanted for [settle] ms; a card
 * scrolled away before then cancels it without decoding anything. Images [inMemory] show at once,
 * so cards scrolled back to, or returned to from a Title, don't blink.
 */
class SettleFirst(private val inMemory: (data: String) -> Boolean, private val settle: Long = SETTLE_MILLIS) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.extras[Key] == true && !inMemory(chain.request.data.toString())) delay(settle)
        return chain.proceed()
    }

    companion object {
        val Key = Extras.Key(default = false)
    }
}


/**
 * Refuses a poster whose file is under [MIN_POSTER_WIDTH] wide (#526): a 154 × 231 file blurs at
 * card size. The Provider's catalog gives no sizes and a card decodes its art at the card's size,
 * so the file's own width is read from its header, before Coil's decoder runs; a request marked
 * with [Key] ([rememberPoster]) then fails with [TooNarrow], and the card takes the Title's next
 * poster. A file whose width can't be read passes. [refused] holds the posters refused while the app runs.
 */
class PosterFloor : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        if (options.extras[Key] != true) return null
        val width = width(result)
        if (width <= 0 || width >= MIN_POSTER_WIDTH) return null
        return Decoder { throw TooNarrow(width) }
    }

    /** The width the file's header names, read without consuming it; -1 when it can't be read. */
    private fun width(result: SourceFetchResult): Int = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        result.source.source().peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        bounds.outWidth
    } catch (e: Exception) {
        -1
    }

    /** A poster's file is [width] px wide, under [MIN_POSTER_WIDTH]. */
    class TooNarrow(val width: Int) : Exception("The poster is $width px wide")

    companion object {
        val Key = Extras.Key(default = false)
        val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
