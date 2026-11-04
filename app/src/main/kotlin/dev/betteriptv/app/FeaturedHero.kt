package dev.betteriptv.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.backdropScrims
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleName
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.FeaturedTitle
import kotlin.math.max

/** The hero's place among a screen's rows, as a [RowCard]'s row: its buttons are its cards. */
internal const val HERO_ROW = "hero"

/** The hero's Play, as a [RowCard] of [HERO_ROW]. */
internal const val HERO_PLAY = "play"

/** The hero's Details, as a [RowCard] of [HERO_ROW]. */
internal const val HERO_DETAILS = "details"

/**
 * The featured [title]'s text column, at the top of Home, Movies or Shows (DESIGN.md, Home): its
 * logo (or name), a metadata line in muted body followed by its badges, two lines of description,
 * then Play and Details 12 dp apart. Its backdrop is drawn behind the whole screen
 * ([featuredBackdrop]). [onFocus] says which button took focus ([HERO_PLAY], [HERO_DETAILS]);
 * focus coming back to the hero returns to the one focused last, the first time to Play.
 */
@Composable
internal fun FeaturedHero(
    title: FeaturedTitle,
    play: FocusRequester,
    details: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFocus: (String) -> Unit,
) {
    val context = LocalContext.current
    // It starts 96 dp under the tabs, which keep 16 dp under them.
    Column(Modifier.padding(start = Spacing.screenX, top = 96.dp - 16.dp, bottom = 28.dp).width(440.dp)) {
        TitleName(title.name, rememberArtwork(title.logo))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val metadata = remember(title) {
                listOfNotNull(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), title.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · ")
            }
            Text(metadata, style = Type.body, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            BadgeRow(title.best.cardBadges())
        }
        title.description?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.focusRestorer(play), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuageButton(
                stringResource(R.string.play), onPlay,
                Modifier.focusRequester(play).onFocusChanged { if (it.isFocused) onFocus(HERO_PLAY) },
                icon = Icons.Filled.PlayArrow,
            )
            NuageButton(
                stringResource(R.string.details), onDetails,
                Modifier.focusRequester(details).onFocusChanged { if (it.isFocused) onFocus(HERO_DETAILS) },
            )
        }
    }
}

/**
 * Draws the featured [title]'s textless backdrop behind what this modifies (a screen of rows, under
 * its ground), cropped to fill it under the [backdropScrims], fading in as it arrives
 * ([rememberArtwork]). It is drawn at [shown], how much of the hero is on screen, so it goes as the
 * first Down scrolls the hero away under the tabs and comes back with it on Up; only the drawing
 * reads that, so scrolling recomposes nothing. Nothing is drawn without a [title].
 */
@Composable
internal fun Modifier.featuredBackdrop(title: FeaturedTitle?, shown: () -> Float): Modifier {
    val backdrop = title?.let { rememberArtwork(it.backdrop) } ?: return this
    val scrims = remember { backdropScrims(Colors.ground) }
    return drawBehind {
        val alpha = shown()
        if (alpha <= 0f) return@drawBehind
        val image = backdrop.intrinsicSize
        if (image.isSpecified && image.width > 0f && image.height > 0f) {
            // Cropped, as ContentScale.Crop: scaled to cover, centred.
            val scale = max(size.width / image.width, size.height / image.height)
            val drawn = Size(image.width * scale, image.height * scale)
            translate((size.width - drawn.width) / 2, (size.height - drawn.height) / 2) {
                with(backdrop) { draw(drawn, alpha) }
            }
        } else {
            with(backdrop) { draw(size, alpha) }
        }
        scrims.forEach { drawRect(it, alpha = alpha) }
    }
}
