package dev.betteriptv.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.TitleName
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Motion
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.FeaturedTitle

/** The art the player shows while a Version starts, until its first frame. */
sealed interface StartingArt {
    /** A Title's (a Movie's, or the Show of an Episode): its backdrop, dimmed, with its logo over it. */
    data class Title(val backdrop: String?, val logo: String?) : StartingArt

    /** A Channel's, a Game's Feed's included: its logo on glass. */
    data class Channel(val logo: String?) : StartingArt
}

/** What the featured hero's Play starts on: the very backdrop and logo the hero drew, so they come from memory. */
internal val FeaturedTitle.startingArt: StartingArt.Title get() = StartingArt.Title(backdrop, logo)

/**
 * What covers the picture while a Version starts, so waiting isn't a black screen: a Title's
 * backdrop dimmed by a `veil`, with its logo (its [name] without one) in the middle, or a Channel's
 * logo on a glass card. The player's starting label sits over it. Once the engine shows its first
 * frame ([pictured]), it fades away over [Motion.screenFadeMillis] of frames ([Fade]) to the picture under it, and
 * leaves the screen. Without [art] (a Sports replay) it is the bare `ground`.
 *
 * Once the session has [failed], the cover is back at full strength whether or not a frame showed (VLC
 * can show its picture and still fail), without its logo or glass card, so the failure's message reads
 * in the centre over the dimmed backdrop instead of on black.
 *
 * The backdrop is the one the Title page drew, so it comes from memory and shows on the first frame.
 */
@Composable
internal fun StartingCover(art: StartingArt?, name: String, pictured: Boolean, failed: Boolean = false) {
    var gone by remember { mutableStateOf(false) }
    // Over frames, not by the clock: the stick can draw a frame every 120 ms as the picture starts,
    // and a fade timed by the clock was a cut (#407).
    val alpha = remember { Fade(1f) }
    LaunchedEffect(pictured) {
        if (!pictured) return@LaunchedEffect
        alpha.fadeOut(Motion.screenFadeMillis)
        gone = true
    }
    if (gone && !failed) return
    Box(Modifier.fillMaxSize().testTag(STARTING_COVER).graphicsLayer { this.alpha = if (failed) 1f else alpha.value }.background(Colors.ground)) {
        when (art) {
            is StartingArt.Title -> {
                rememberArtwork(art.backdrop)?.let { backdrop ->
                    Image(backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag(STARTING_BACKDROP))
                }
                // Dimmed, so the logo and the starting label read over any backdrop.
                Box(Modifier.fillMaxSize().background(Colors.veil))
                if (!failed) TitleName(name, rememberArtwork(art.logo), Modifier.align(Alignment.Center))
            }
            is StartingArt.Channel -> if (!failed) Box(
                Modifier.align(Alignment.Center).width(Cards.continueWidth).aspectRatio(16f / 9f)
                    .background(Colors.surface, RoundedCornerShape(Corners.card))
                    .testTag(STARTING_GLASS),
            ) {
                val logo = rememberArtwork(art.logo)
                if (logo != null) {
                    // Logos come in every shape: shown whole, never cropped.
                    Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(Spacing.xl))
                } else {
                    Text(name, style = Type.headline, color = Colors.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.l))
                }
            }
            null -> Unit
        }
    }
}

internal const val STARTING_COVER = "starting-cover"
internal const val STARTING_BACKDROP = "starting-backdrop"
internal const val STARTING_GLASS = "starting-glass"
