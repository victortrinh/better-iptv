package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * The first row of a details page's action list: a glass capsule naming exactly what it plays
 * ("Continue from 1:02:13", "Play S1 E1"), with how far in the Viewer is ([progress]) as a short bar
 * inside it. It spans the list's width and doesn't grow on focus, like the rows under it.
 */
@Composable
fun PrimaryAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    enabled: Boolean = true,
) {
    ActionSurface(text, icon, { if (enabled) onClick() }, modifier, container = Colors.surfaceHigh, enabled = enabled) { ink ->
        if (progress != null) {
            Spacer(Modifier.width(12.dp))
            val bar = RoundedCornerShape(50)
            Box(Modifier.width(PROGRESS_WIDTH).height(4.dp).clip(bar).background(ink.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(ink))
            }
        }
    }
}

/**
 * A row of a details page's action list under the [PrimaryAction]: an icon and a label with no
 * container at rest, a white capsule only while focused.
 */
@Composable
fun ActionRow(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionSurface(text, icon, onClick, modifier, container = Color.Transparent)
}

@Composable
private fun ActionSurface(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    enabled: Boolean = true,
    trailing: @Composable (ink: Color) -> Unit = {},
) {
    FocusSurface(onClick, modifier.fillMaxWidth().height(40.dp), weight = FocusWeight.Row, container = container, focusedContainer = Colors.focus) { focused ->
        val ink = when {
            !enabled -> Colors.inkMuted
            focused -> Colors.onFocus
            else -> Colors.ink
        }
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = Type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            trailing(ink)
        }
    }
}

private val PROGRESS_WIDTH = 48.dp
