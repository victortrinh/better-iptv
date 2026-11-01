package dev.betteriptv.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.betteriptv.app.ui.components.CardLogo
import coil3.Extras
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Precision
import kotlinx.coroutines.delay

/**
 * The artwork at [url] (TMDB's, or the Provider's), loaded in the background; null when there is
 * none or it couldn't be loaded, so the caller can show the Title's name instead. Only a failure
 * recomposes the caller: the painter redraws itself when the image arrives.
 *
 * It is decoded at the size it's drawn, not TMDB's. A [card]'s artwork waits until the card has
 * been on screen a moment ([SettleFirst]): holding Down through a grid brings a row of cards in at
 * every press, and decoding all their posters kept the stick's garbage collector running (#33).
 */
@Composable
fun rememberArtwork(url: String?, card: Boolean = false): Painter? {
    if (url == null) return null
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val request = remember(url, card) {
        ImageRequest.Builder(context).data(url).precision(Precision.EXACT).apply { if (card) extras[SettleFirst.Key] = true }.build()
    }
    val painter = rememberAsyncImagePainter(request, onError = { failed = true })
    return if (failed) null else painter
}

/**
 * Holds back an image marked in its request until it has been wanted for [settle] ms; a card
 * scrolled away before then cancels it without decoding anything. Images [inMemory] show at once,
 * so cards scrolled back to, or returned to from a Title, don't blink.
 */
class SettleFirst(private val inMemory: (data: String) -> Boolean, private val settle: Long = 250) : Interceptor {
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
 * when TMDB has no logo. The backdrop is loaded only while the card is grown, and like any card's
 * artwork once focus has rested on it a moment; the glass and the name show until then.
 */
@Composable
fun BoxScope.GrownArtwork(art: GrownArt, name: String) {
    val backdrop = rememberArtwork(art.backdrop, card = true)
    if (backdrop != null) Box(Modifier.matchParentSize().paint(backdrop, contentScale = ContentScale.Crop).semantics { contentDescription = name })
    val logo = (art as? GrownArt.WithLogo)?.logo
    CardLogo(rememberArtwork(logo, card = true), logo != null, name, wide = true)
}
