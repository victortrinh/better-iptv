package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.FocusScale
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * One Game (CONTEXT.md): both teams' logos on a 16:9 glass card, the away team's ([away]) on the
 * left and the home team's ([home]) on the right, each whole and never cropped, with the [caption]
 * ("Live", or when it starts) at the bottom left. A logo that hasn't loaded or can't be shows its
 * team's short name in its place instead, so a card is never blank. With [disabled] set (an
 * upcoming Game with no Feed), the card dims and says why at the bottom right; it still takes
 * focus, so the info under the rail can tell about it, but pressing it does nothing. Never a score.
 * The card carries no name slot: on Live the info under the rail names the Game. In a row that mixes
 * Leagues (Live now, Today), the Game's [league] logo sits small at the top left, so the Viewer knows
 * whose Game it is; it is left out while it hasn't loaded or can't be.
 */
@Composable
fun GameCard(
    away: Painter?,
    home: Painter?,
    awayName: String,
    homeName: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabled: String? = null,
    onFocus: () -> Unit = {},
    league: Painter? = null,
) {
    GameCardFrame(caption, onClick, modifier, disabled, onFocus, league) {
        TeamLogo(away, awayName, Modifier.weight(1f).fillMaxHeight().testTag("away"))
        TeamLogo(home, homeName, Modifier.weight(1f).fillMaxHeight().testTag("home"))
    }
}

/**
 * An event League's Game (F1's, #310), which has no teams: the League's [logo] on the left, where
 * the away team's goes, or its [leagueName] while there is none, and the event's [name] over its
 * [session] in label type on the right, where the home team's goes. The rest is a [GameCard]'s.
 */
@Composable
fun EventGameCard(
    logo: Painter?,
    leagueName: String,
    name: String,
    session: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabled: String? = null,
    onFocus: () -> Unit = {},
) {
    GameCardFrame(caption, onClick, modifier, disabled, onFocus) {
        TeamLogo(logo, leagueName, Modifier.weight(1f).fillMaxHeight().testTag("league"))
        Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = LOGO_ROOM_FOR_CAPTION).testTag("event"), verticalArrangement = Arrangement.Center) {
            Text(name, style = Type.label, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(session, style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A Game card's glass, its [caption] at the bottom left, its [league] badge at the top left and its [disabled] state, around the [sides] it lays out in a row. */
@Composable
private fun GameCardFrame(
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier,
    disabled: String?,
    onFocus: () -> Unit,
    league: Painter? = null,
    sides: @Composable RowScope.() -> Unit,
) {
    FocusSurface(
        { if (disabled == null) onClick() },
        modifier.width(Cards.wideWidth).aspectRatio(16f / 9f),
        cardScale = FocusScale.wideCard,
        container = Colors.surface,
        onFocus = onFocus,
    ) {
        Row(Modifier.fillMaxSize().alpha(if (disabled == null) 1f else DISABLED_ALPHA).padding(horizontal = Spacing.m, vertical = Spacing.s), content = sides)
        if (league != null) {
            Image(
                league,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopStart).padding(start = Spacing.s, top = CAPTION_BOTTOM).size(LEAGUE_BADGE)
                    .alpha(if (disabled == null) 1f else DISABLED_ALPHA).testTag("league-badge"),
            )
        }
        Text(
            caption,
            style = Type.caption,
            color = Colors.ink,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Spacing.s, bottom = CAPTION_BOTTOM).alpha(if (disabled == null) 1f else DISABLED_ALPHA),
        )
        if (disabled != null) {
            Text(
                disabled,
                style = Type.caption,
                color = Colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.s, bottom = CAPTION_BOTTOM).fillMaxWidth(0.6f),
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A team's [logo], fitted whole and centred, or its [name] in label type while there is none. */
@Composable
private fun TeamLogo(logo: Painter?, name: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (logo != null) {
            Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(bottom = LOGO_ROOM_FOR_CAPTION))
        } else {
            Text(
                name,
                style = Type.label,
                color = Colors.ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = LOGO_ROOM_FOR_CAPTION),
            )
        }
    }
}

/** The caption's line and the margin under it, kept clear under the logos. */
private val LOGO_ROOM_FOR_CAPTION = 14.dp
private val CAPTION_BOTTOM = 6.dp

/** The League's logo at a mixed row's card's top left: about a caption line tall. */
private val LEAGUE_BADGE = 18.dp
private const val DISABLED_ALPHA = 0.45f
