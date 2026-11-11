package dev.betteriptv.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleName
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.inLayoutDirection
import dev.betteriptv.core.FeaturedTitle

/** The hero's place among a screen's rows, as a [RowCard]'s row: its buttons are its cards. */
internal const val HERO_ROW = "hero"

/** The hero's Play, as a [RowCard] of [HERO_ROW]. */
internal const val HERO_PLAY = "play"

/** The hero's Details, as a [RowCard] of [HERO_ROW]. */
internal const val HERO_DETAILS = "details"

/** The hero's poster, for tests. */
internal const val HERO_POSTER = "hero poster"

/**
 * The featured [title] at the top of Home, Movies or Shows (DESIGN.md, Home), on the bare ground: its
 * text column, 440 dp wide, and its poster at the right margin. The column holds its name in Display
 * type ([TitleName]), a metadata line in muted body followed by its badges (those of the Version its
 * Play starts, [FeaturedTitle.badge]; none when it has none), two lines of description, then Play
 * and Details 12 dp apart. The poster is the one its card shows (the first of its posters the floor
 * doesn't refuse, [rememberPoster]), 136 dp wide, 2:3, `card` corners, top-aligned with the name;
 * none when the Title has none. [onFocus] says which button took focus ([HERO_PLAY], [HERO_DETAILS]);
 * focus coming into the hero from outside lands on the one focused [last], else on Play: the hero
 * leaves composition once scrolled away, so a focus restorer would forget it, and Up from the first
 * row lands on whichever button is nearer.
 */
@Composable
internal fun FeaturedHero(
    title: FeaturedTitle,
    play: FocusRequester,
    details: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFocus: (String) -> Unit,
    last: String = HERO_PLAY,
) {
    val context = LocalContext.current
    // While focus is on a button, Left and Right move between them; from outside, only [last] takes it.
    var inside by remember { mutableStateOf(false) }
    // It starts 96 dp under the tabs, which keep 16 dp under them.
    Row(Modifier.fillMaxWidth().padding(start = Spacing.screenX, end = Spacing.screenX, top = 96.dp - 16.dp, bottom = 28.dp)) {
        Column(Modifier.width(440.dp)) {
            TitleName(title.name)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val metadata = remember(title) {
                    metaLine(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), genresPart(title.genres))
                }
                Text(metadata, style = Type.body.inLayoutDirection(), color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // The Version its Play starts, as on its page (#521); none on a Show whose Episodes were never read.
                // Its quality only once Confirmed, and the row keeps its height meanwhile, so nothing under it moves (#546).
                Spacer(Modifier.width(12.dp))
                BadgeRow(title.badge?.cardBadges().orEmpty(), holdPlace = true)
            }
            title.description?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.onFocusChanged { inside = it.hasFocus }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuageButton(
                    stringResource(R.string.play), onPlay,
                    Modifier.focusProperties { canFocus = inside || last != HERO_DETAILS }.focusRequester(play).onFocusChanged { if (it.isFocused) onFocus(HERO_PLAY) },
                    icon = Icons.Filled.PlayArrow,
                )
                NuageButton(
                    stringResource(R.string.details), onDetails,
                    Modifier.focusProperties { canFocus = inside || last == HERO_DETAILS }.focusRequester(details).onFocusChanged { if (it.isFocused) onFocus(HERO_DETAILS) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // The same poster, at the same size, as its card and its page: from memory once any of them drew it.
        rememberPoster(title.posters)?.let { poster ->
            Image(
                poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(Cards.posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(Corners.card)).testTag(HERO_POSTER),
            )
        }
    }
}
