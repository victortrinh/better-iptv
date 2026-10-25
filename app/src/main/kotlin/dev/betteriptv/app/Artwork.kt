package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * The artwork at [url] (TMDB's, or the Provider's), loaded in the background; null when there is
 * none or it couldn't be loaded, so the caller can show the Title's name instead.
 */
@Composable
fun rememberArtwork(url: String?): Painter? {
    if (url == null) return null
    val painter = rememberAsyncImagePainter(url)
    val state by painter.state.collectAsState()
    return if (state is AsyncImagePainter.State.Error) null else painter
}
