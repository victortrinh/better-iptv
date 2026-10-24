package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.LikelyMark
import dev.betteriptv.app.ui.theme.Nuage

/** How sure the app is of a Version attribute. */
enum class Certainty {
    /** Read from the file: a Header read or Playback. */
    Confirmed,
    /** Inferred from the Prefix, Category or the Title's original language. */
    Likely,
}

data class BadgeSpec(val text: String, val certainty: Certainty)

/** One Version attribute. A Confirmed badge is solid, a Likely badge is outlined, in every direction. */
@Composable
fun Badge(spec: BadgeSpec, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = Nuage.colors
    val corner = Nuage.shape.badge
    val shape = RoundedCornerShape(corner)
    val decorated = when (spec.certainty) {
        Certainty.Confirmed -> modifier.background(colors.confirmed, shape)
        Certainty.Likely -> when (Nuage.direction.likelyMark) {
            LikelyMark.Outline -> modifier.border(1.5.dp, colors.likely, shape)
            LikelyMark.Dashed -> modifier.drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    colors.likely,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
        }
    }
    Box(decorated.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp)) {
        Text(
            spec.text,
            style = Nuage.type.caption,
            color = if (spec.certainty == Certainty.Confirmed) colors.onConfirmed else colors.likely,
            maxLines = 1,
        )
    }
}

@Composable
fun BadgeRow(badges: List<BadgeSpec>, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { Badge(it, compact = compact) }
    }
}

/** Explains the two badge styles, for household members who didn't set the app up. */
@Composable
fun CertaintyLegend(modifier: Modifier = Modifier) {
    val colors = Nuage.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Badge(BadgeSpec(stringResource(R.string.badge_confirmed), Certainty.Confirmed))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.badge_confirmed_meaning), style = Nuage.type.caption, color = colors.inkMuted)
        Spacer(Modifier.width(16.dp))
        Badge(BadgeSpec(stringResource(R.string.badge_likely), Certainty.Likely))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.badge_likely_meaning), style = Nuage.type.caption, color = colors.inkMuted)
        Spacer(Modifier.size(0.dp))
    }
}
