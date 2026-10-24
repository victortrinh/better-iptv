package dev.betteriptv.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Nuage

/** What a Version row shows. Strings arrive formatted; the row only lays them out. */
data class VersionLine(
    val badges: List<BadgeSpec>,
    val source: String,
    val audio: String,
    val subtitles: String,
    val bitrate: String?,
    val backups: String?,
    /** A warning or explanation under the row: "Likely English", "Cinema recording (CAM)". */
    val note: String? = null,
    val noteIsCaution: Boolean = false,
)

/**
 * One Version in a Title's list. Columns have fixed widths so the Versions of a language line up
 * like a table and can be compared at a glance.
 */
@Composable
fun VersionRow(line: VersionLine, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Nuage.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Nuage.shape.control)
    FocusSurface(
        onClick,
        // A row in a list doesn't grow on focus; an accent outline marks it instead.
        modifier.fillMaxWidth().then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier),
        weight = FocusWeight.Control,
        grow = false,
        container = colors.surface,
        focusedContainer = colors.surfaceHigh,
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(196.dp)) {
                BadgeRow(line.badges)
                if (line.note != null) {
                    Text(
                        line.note,
                        style = Nuage.type.caption,
                        color = if (line.noteIsCaution) colors.caution else colors.inkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1,
                    )
                }
            }
            Cell(line.source, 96.dp, emphasis = true)
            Cell(line.audio, 196.dp)
            Cell(line.subtitles, 116.dp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    line.bitrate?.let { Text(it, style = Nuage.type.body, color = colors.ink, textAlign = TextAlign.End) }
                    line.backups?.let { Text(it, style = Nuage.type.caption, color = colors.inkMuted) }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, emphasis: Boolean = false) {
    Text(
        text,
        style = if (emphasis) Nuage.type.label else Nuage.type.body,
        color = if (emphasis) Nuage.colors.ink else Nuage.colors.inkMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(end = 12.dp),
    )
}

