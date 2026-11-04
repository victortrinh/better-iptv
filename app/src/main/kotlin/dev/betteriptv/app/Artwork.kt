package dev.betteriptv.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.betteriptv.app.ui.components.CardLogo
import dev.betteriptv.app.ui.theme.Motion
import coil3.Extras
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Precision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The artwork at [url] (TMDB's, or the Provider's), loaded in the background; null when there is
 * none or it couldn't be loaded, so the caller can show the Title's name instead. Only a failure
 * recomposes the caller: the painter redraws itself when the image arrives.
 *
 * It is decoded at the size it's drawn, not TMDB's. A [card]'s artwork waits until the card has
 * been on screen a moment ([SettleFirst]): holding Down through a grid brings a row of cards in at
 * every press, and decoding all their posters kept the stick's garbage collector running (#33).
 *
 * It arrives, it doesn't pop: an image read from disk or the network fades in over
 * [Motion.imageFadeMillis], and one already in the memory cache shows on its first frame, so going
 * back to a screen stays instant ([FadeIn]).
 */
@Composable
fun rememberArtwork(url: String?, card: Boolean = false): Painter? {
    if (url == null) return null
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val request = remember(url, card) { artworkRequest(context, url, card) }
    val image = rememberAsyncImagePainter(request, onError = { failed = true })
    val fade = remember(url) { Animatable(0f) }
    LaunchedEffect(image, fade) {
        // This url's image, not the one the painter showed before it.
        val arrived = image.state.first { it is AsyncImagePainter.State.Success && it.result.request.data == url } as AsyncImagePainter.State.Success
        if (arrived.result.dataSource == DataSource.MEMORY_CACHE) fade.snapTo(1f) else fade.animateTo(1f, tween(Motion.imageFadeMillis))
    }
    val painter = remember(image, fade) { FadeIn(image, fade) }
    return if (failed) null else painter
}

/** The request for the artwork at [url]; a [card]'s waits to settle first ([SettleFirst]). Its memory cache key is the url alone. */
private fun artworkRequest(context: PlatformContext, url: String, card: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(url).precision(Precision.EXACT).apply { if (card) extras[SettleFirst.Key] = true }.build()

/**
 * Loads the [backdrop] a poster card grows into ([GrownArtwork]) into the memory cache, so the card
 * grows straight into its art when it is focused next, instead of growing as glass and fading it
 * in. It is the image [rememberArtwork] asks for, at the original size its painter loads it at, so
 * the grown card finds it there on its first grown frame.
 */
fun preloadGrownBackdrop(context: PlatformContext, backdrop: String) {
    SingletonImageLoader.get(context).enqueue(artworkRequest(context, backdrop, card = false).newBuilder().size(coil3.size.Size.ORIGINAL).build())
}

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
private class FadeIn(private val image: AsyncImagePainter, private val fade: Animatable<Float, *>) : Painter() {
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
 * What a focused poster card grows into ([TitleCard]'s `grown`): its Title's textless backdrop,
 * cropped to the card, with the logo at its bottom left as on a still, or the [name] in title type
 * when TMDB has no logo. The backdrop is loaded while the card is grown, like any card's artwork
 * once focus has rested on it a moment, unless it is in memory already: the rows load the backdrops
 * of the cards beside the one focus rests on ahead ([preloadGrownBackdrop]). Until it arrives the
 * glass and the name show, and it fades in.
 */
@Composable
fun BoxScope.GrownArtwork(art: GrownArt, name: String) {
    val backdrop = rememberArtwork(art.backdrop, card = true)
    if (backdrop != null) Box(Modifier.matchParentSize().paint(backdrop, contentScale = ContentScale.Crop).semantics { contentDescription = name })
    val logo = (art as? GrownArt.WithLogo)?.logo
    CardLogo(rememberArtwork(logo, card = true), logo != null, name, wide = true)
}
