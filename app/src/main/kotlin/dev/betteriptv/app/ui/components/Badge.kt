package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type

/** How sure the app is of a Version attribute. */
enum class Certainty {
    /** Read from the file: a Header read or Playback. */
    Confirmed,
    /** Inferred from the Prefix, Category or the Title's original language. */
    Likely,
}

data class BadgeSpec(val text: String, val certainty: Certainty)

/**
 * One Version attribute. A Confirmed badge is solid, a Likely badge is outlined, everywhere badges
 * appear. The difference is in the shape, not only the colour.
 */
@Composable
fun Badge(spec: BadgeSpec, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(Corners.badge)
    val decorated = when (spec.certainty) {
        Certainty.Confirmed -> modifier.background(Colors.confirmed, shape)
        Certainty.Likely -> modifier.border(1.5.dp, Colors.likely, shape)
    }
    Box(decorated.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp)) {
        Text(
            spec.text,
            style = Type.caption,
            color = if (spec.certainty == Certainty.Confirmed) Colors.onConfirmed else Colors.likely,
            maxLines = 1,
        )
    }
}

/** Badges side by side. [compact] trims their padding to fit under a poster. */
@Composable
fun BadgeRow(badges: List<BadgeSpec>, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { Badge(it, compact = compact) }
    }
}

/** Explains the two badge styles, for household members who didn't set the app up. */
@Composable
fun CertaintyLegend(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Badge(BadgeSpec(stringResource(R.string.badge_confirmed), Certainty.Confirmed))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.badge_confirmed_meaning), style = Type.caption, color = Colors.inkMuted)
        Spacer(Modifier.width(16.dp))
        Badge(BadgeSpec(stringResource(R.string.badge_likely), Certainty.Likely))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.badge_likely_meaning), style = Type.caption, color = Colors.inkMuted)
    }
}
