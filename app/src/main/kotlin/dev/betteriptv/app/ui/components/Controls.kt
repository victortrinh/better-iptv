package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.FocusKind
import dev.betteriptv.app.ui.theme.Nuage
import dev.betteriptv.app.ui.theme.TabStyle

/**
 * A button. Focus fills it with the accent, the one colour reserved for what the Viewer can act on.
 * A [primary] button (Play) keeps an ink fill when unfocused so it stands out before it has focus.
 */
@Composable
fun NuageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = Nuage.colors
    val inkFill = primary && !Nuage.direction.glass
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(40.dp),
        weight = FocusWeight.Control,
        container = if (inkFill) colors.ink else colors.surfaceHigh,
        focusedContainer = colors.accent,
    ) { focused ->
        val content = when {
            !enabled -> colors.inkMuted
            focused -> colors.onAccent
            inkFill -> colors.ground
            else -> colors.ink
        }
        Row(Modifier.align(Alignment.Center).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Nuage.type.label, color = content, maxLines = 1)
        }
    }
}

/** The app's name, set in the direction's display face. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.app_name),
        style = Nuage.type.headline.copy(fontSize = 22.sp),
        color = Nuage.colors.ink,
        modifier = modifier,
    )
}

/** The top navigation: the wordmark, then one tab per destination. Reached by pressing up. */
@Composable
fun TopTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Nuage.colors
    val style = Nuage.direction.tabs
    val underline = Nuage.direction.focus in setOf(FocusKind.OffsetFrame, FocusKind.Underline, FocusKind.Mat)
    Box(modifier.fillMaxWidth()) {
        Wordmark(Modifier.align(Alignment.CenterStart))
        val tabsModifier = when (style) {
            TabStyle.Inline -> Modifier.align(Alignment.CenterStart).padding(start = 140.dp)
            TabStyle.Centered -> Modifier.align(Alignment.Center)
            TabStyle.GlassPill -> Modifier.align(Alignment.Center)
                .background(colors.surface, RoundedCornerShape(50))
                .border(1.dp, colors.ink.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(4.dp)
        }
        Row(tabsModifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, tab ->
                FocusSurface(
                    onClick = { onSelect(index) },
                    weight = FocusWeight.Control,
                    corner = if (style == TabStyle.Inline) Nuage.shape.control else 20.dp,
                    container = if (style == TabStyle.GlassPill && index == selected) colors.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainer = if (underline) androidx.compose.ui.graphics.Color.Transparent else colors.accent,
                ) { focused ->
                    val ink = when {
                        focused && !underline -> colors.onAccent
                        focused || index == selected -> colors.ink
                        else -> colors.inkMuted
                    }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tab, style = Nuage.type.label, color = ink)
                        if (underline) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.width(24.dp).height(3.dp).background(
                                    when {
                                        focused -> colors.accent
                                        index == selected -> colors.inkMuted
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A settings row: what it is on the left, its current value on the right. */
@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = Nuage.colors
    FocusSurface(onClick, modifier.fillMaxWidth(), weight = FocusWeight.Control, grow = false, container = colors.surface, focusedContainer = colors.accent) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Nuage.type.label, color = if (focused) colors.onAccent else colors.ink)
                if (detail != null) {
                    Text(detail, style = Nuage.type.caption, color = if (focused) colors.onAccent.copy(alpha = 0.8f) else colors.inkMuted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(value, style = Nuage.type.body, color = if (focused) colors.onAccent else colors.inkMuted)
        }
    }
}

/** A small set of exclusive choices (Quality cap, Background reading). */
@Composable
fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Nuage.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            FocusSurface(
                onClick = { onSelect(index) },
                modifier = Modifier.height(36.dp).then(
                    if (isSelected) Modifier else Modifier.border(1.5.dp, colors.inkMuted.copy(alpha = 0.5f), RoundedCornerShape(Nuage.shape.control)),
                ),
                weight = FocusWeight.Control,
                container = if (isSelected) colors.ink else colors.surface,
                focusedContainer = colors.accent,
            ) { focused ->
                Text(
                    option,
                    style = Nuage.type.label,
                    color = when {
                        focused -> colors.onAccent
                        isSelected -> colors.ground
                        else -> colors.ink
                    },
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/**
 * An on-screen keyboard laid out for the D-pad: letters in alphabetical order on a 7-column grid, so
 * any letter is at most a few presses away and the layout is the same in English and French.
 */
@Composable
fun RemoteKeyboard(onKey: (String) -> Unit, onDelete: () -> Unit, onSpace: () -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf("abcdefg", "hijklmn", "opqrstu", "vwxyz12", "3456789", "0'-")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { char -> Key(char.toString(), { onKey(char.toString()) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.keyboard_space), onSpace, width = KEY * 3 + 6.dp * 2)
            Key(stringResource(R.string.keyboard_delete), onDelete, width = KEY * 4 + 6.dp * 3)
        }
    }
}

private val KEY = 34.dp

@Composable
private fun Key(label: String, onClick: () -> Unit, width: Dp = KEY) {
    val colors = Nuage.colors
    FocusSurface(onClick, Modifier.size(width, KEY), weight = FocusWeight.Control, container = colors.surface, focusedContainer = colors.accent) { focused ->
        Text(
            label,
            style = Nuage.type.label,
            color = if (focused) colors.onAccent else colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
