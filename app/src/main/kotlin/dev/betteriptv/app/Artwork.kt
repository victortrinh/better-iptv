package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
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
