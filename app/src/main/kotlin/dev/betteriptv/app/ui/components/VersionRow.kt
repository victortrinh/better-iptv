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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.VersionColumn

/** What a Version row shows. Strings arrive formatted; the row only lays them out. */
data class VersionLine(
    val badges: List<BadgeSpec>,
    val source: String,
    val audio: String,
    val subtitles: String,
    val bitrate: String?,
    /** "Backup" on a Backup's row, which sits under its Version's. */
    val backup: String?,
    /** A warning or explanation under the badges: "Likely English", "Cinema recording (CAM)". */
    val note: String? = null,
    val noteIsCaution: Boolean = false,
    /** A Channel's label, which stands in for the columns: "Live · MPEG-TS". */
    val live: String? = null,
    /** Every cell in muted type, for a Backup under its Version on the Versions screen. */
    val muted: Boolean = false,
)

/**
 * One Version in a Title's list. Columns have fixed widths so the Versions of a language line up
 * like a table and can be compared at a glance. Only [columns] are drawn, the room of the others
 * shared among them ([VersionHeader] takes the same [columns]).
 */
@Composable
fun VersionRow(line: VersionLine, onClick: () -> Unit, modifier: Modifier = Modifier, columns: List<VersionColumn> = VersionColumn.entries) {
    val widths = columnWidths(columns)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corners.control)
    FocusSurface(
        onClick,
        // A row in a list doesn't grow on focus, and its content stays readable on glass, so a
        // white outline marks it instead of a white fill.
        modifier.fillMaxWidth().then(if (focused) Modifier.border(2.dp, Colors.focus, shape) else Modifier),
        weight = FocusWeight.Row,
        container = Colors.surface,
        focusedContainer = Colors.surfaceHigh,
        interactionSource = interaction,
    ) {
        if (line.live != null) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(line.live, style = Type.label, color = Colors.ink, maxLines = 1)
                line.note?.let { Text(it, style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(top = 4.dp), maxLines = 1) }
            }
        } else {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                widths[VersionColumn.Quality]?.let { width ->
                    Column(Modifier.width(width)) {
                        BadgeRow(line.badges)
                        if (line.note != null) {
                            Text(
                                line.note,
                                style = Type.caption,
                                color = if (line.noteIsCaution) Colors.caution else Colors.inkMuted,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
                widths[VersionColumn.Source]?.let { Cell(line.source, it, emphasis = !line.muted) }
                widths[VersionColumn.Audio]?.let { Cell(line.audio, it) }
                widths[VersionColumn.Subtitles]?.let { Cell(line.subtitles, it) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Column(horizontalAlignment = Alignment.End) {
                        line.bitrate?.takeIf { VersionColumn.Bitrate in columns }?.let {
                            Text(it, style = Type.detail, color = if (line.muted) Colors.inkMuted else Colors.ink, textAlign = TextAlign.End)
                        }
                        line.backup?.let { Text(it, style = Type.caption, color = Colors.inkMuted) }
                    }
                }
            }
        }
    }
}

/** Names the [columns] of the [VersionRow]s under it. */
@Composable
fun VersionHeader(modifier: Modifier = Modifier, columns: List<VersionColumn> = VersionColumn.entries) {
    val widths = columnWidths(columns)
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        widths[VersionColumn.Quality]?.let { Heading(stringResource(R.string.version_quality), it) }
        widths[VersionColumn.Source]?.let { Heading(stringResource(R.string.version_source), it) }
        widths[VersionColumn.Audio]?.let { Heading(stringResource(R.string.version_audio), it) }
        widths[VersionColumn.Subtitles]?.let { Heading(stringResource(R.string.version_subtitles), it) }
        val bitrate = if (VersionColumn.Bitrate in columns) stringResource(R.string.version_bitrate) else ""
        Text(bitrate, style = Type.caption, color = Colors.inkMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// Fixed column widths, sized for French labels about 20% longer than English. Bitrate takes the rest of the row.
private val WIDTHS = mapOf(VersionColumn.Quality to 188.dp, VersionColumn.Source to 124.dp, VersionColumn.Audio to 188.dp, VersionColumn.Subtitles to 120.dp)

/** The width of each of [columns] but Bitrate: its own, plus an even share of the hidden columns'. */
private fun columnWidths(columns: List<VersionColumn>): Map<VersionColumn, Dp> {
    val shown = WIDTHS.filterKeys { it in columns }
    if (shown.isEmpty()) return shown
    val share = WIDTHS.filterKeys { it !in columns }.values.fold(0.dp) { sum, width -> sum + width } / shown.size
    return shown.mapValues { (_, width) -> width + share }
}

@Composable
private fun Heading(text: String, width: Dp) {
    Text(text, style = Type.caption, color = Colors.inkMuted, maxLines = 1, modifier = Modifier.width(width).padding(end = 12.dp))
}

@Composable
private fun Cell(text: String, width: Dp, emphasis: Boolean = false) {
    Text(
        text,
        style = if (emphasis) Type.label else Type.detail,
        color = if (emphasis) Colors.ink else Colors.inkMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(end = 12.dp),
    )
}
