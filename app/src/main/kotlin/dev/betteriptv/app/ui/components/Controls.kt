package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/** A glass capsule button. Focus turns it solid white; nothing else in the app is white-filled. */
@Composable
fun NuageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(40.dp),
        weight = FocusWeight.Control,
        container = Colors.surfaceHigh,
        focusedContainer = Colors.focus,
    ) { focused ->
        val content = when {
            !enabled -> Colors.inkMuted
            focused -> Colors.onFocus
            else -> Colors.ink
        }
        Row(Modifier.align(Alignment.Center).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Type.label, color = content, maxLines = 1)
        }
    }
}

/**
 * A round glass button holding only an icon, for the player's transport. [name] is what it does: it
 * shows in a caption under the button while it has focus, so someone who has never seen the icon
 * still learns it, and it is the icon's description. The caption's slot is always reserved, so
 * nothing moves as focus does. [slot] centres a smaller button in a bigger one's height, so a row of
 * them lines up their icons and their captions.
 */
@Composable
fun NuageIconButton(icon: ImageVector, name: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, slot: Dp = size) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(slot)) {
            FocusSurface(
                onClick = onClick,
                modifier = modifier.align(Alignment.Center).size(size),
                weight = FocusWeight.Control,
                corner = size / 2,
                container = Colors.surfaceHigh,
                focusedContainer = Colors.focus,
                interactionSource = interaction,
            ) { isFocused ->
                Icon(icon, contentDescription = name, tint = if (isFocused) Colors.onFocus else Colors.ink, modifier = Modifier.align(Alignment.Center).size(size / 2))
            }
        }
        Box(Modifier.width(slot).padding(top = 8.dp).height(16.dp)) {
            if (focused) {
                // Wider than the button: centred under it, spilling past its sides.
                Text(name, style = Type.caption, color = Colors.ink, maxLines = 1, modifier = Modifier.align(Alignment.Center).wrapContentWidth(unbounded = true))
            }
        }
    }
}

/** A destination in [TopTabs]: a word, or an icon with [name] as its description. */
data class TopTab(val name: String, val icon: ImageVector? = null)

/** The app's name, set lowercase like the TV banner. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.wordmark), style = Type.headline.copy(fontSize = 22.sp), color = Colors.ink, modifier = modifier)
}

/**
 * The top navigation: the wordmark on the left and the destinations in a glass capsule in the
 * middle, reached by pressing up. A tab with an icon is a round icon the height of a word tab.
 * [selectedFocus] focuses the selected tab, for Back from the screen below (see `backToTab`).
 */
@Composable
fun TopTabs(tabs: List<TopTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, selectedFocus: FocusRequester? = null) {
    val capsule = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth()) {
        Wordmark(Modifier.align(Alignment.CenterStart))
        Row(
            Modifier.align(Alignment.Center)
                .background(Colors.surface, capsule)
                .border(1.dp, Colors.ink.copy(alpha = 0.14f), capsule)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                FocusSurface(
                    onClick = { onSelect(index) },
                    // One fixed height for every tab: sized by its content, an icon tab came out taller
                    // than a word tab, whose text renders shorter than the label's line height.
                    modifier = (if (tab.icon != null) Modifier.size(TAB_HEIGHT) else Modifier.height(TAB_HEIGHT))
                        .then(if (index == selected && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier),
                    weight = FocusWeight.Control,
                    corner = TAB_HEIGHT / 2,
                    container = if (index == selected) Colors.surfaceHigh else Color.Transparent,
                    focusedContainer = Colors.focus,
                ) { focused ->
                    // Inactive tabs sit on glass over artwork; muted ink would drop below 4.5:1 there.
                    val ink = when {
                        focused -> Colors.onFocus
                        index == selected -> Colors.ink
                        else -> Colors.ink.copy(alpha = 0.85f)
                    }
                    if (tab.icon != null) {
                        Icon(tab.icon, contentDescription = tab.name, tint = ink, modifier = Modifier.align(Alignment.Center).size(20.dp))
                    } else {
                        Text(tab.name, style = Type.label, color = ink, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}

private val TAB_HEIGHT = 32.dp

/**
 * A settings row: what it is on the left, its current value on the right. [progress] (0 to 1) draws
 * how far the Viewer got under the label, as on a card. A row that isn't [enabled] keeps focus but
 * ignores the click, its label muted like a disabled button's.
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float? = null,
    enabled: Boolean = true,
) {
    FocusSurface({ if (enabled) onClick() }, modifier.fillMaxWidth(), weight = FocusWeight.Row, container = Colors.surface, focusedContainer = Colors.focus) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.label, color = if (focused) Colors.onFocus else if (enabled) Colors.ink else Colors.inkMuted)
                if (detail != null) {
                    Text(detail, style = Type.caption, color = if (focused) Colors.onFocus.copy(alpha = 0.7f) else Colors.inkMuted)
                }
                if (progress != null) {
                    val ink = if (focused) Colors.onFocus else Colors.ink
                    Box(Modifier.padding(top = 6.dp).width(PROGRESS_WIDTH).height(4.dp).background(ink.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(ink, RoundedCornerShape(50)))
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(value, style = Type.body, color = if (focused) Colors.onFocus else Colors.inkMuted)
        }
    }
}

/** How wide a row's progress bar is: about a card's, so a started Episode reads like a started Movie. */
private val PROGRESS_WIDTH = 120.dp

/**
 * A small set of exclusive choices (Quality cap, Background reading). The chosen one is stronger
 * glass with a check; only focus is white. Choices that don't fit beside their label move whole to
 * a second line; a choice's word never breaks. [selectedModifier] goes on the chosen one, to focus it.
 */
@Composable
fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, selectedModifier: Modifier = Modifier) {
    val capsule = RoundedCornerShape(50)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            FocusSurface(
                onClick = { onSelect(index) },
                modifier = Modifier.height(36.dp).then(if (isSelected) selectedModifier else Modifier.border(1.5.dp, Colors.inkMuted.copy(alpha = 0.5f), capsule)),
                weight = FocusWeight.Control,
                container = if (isSelected) Colors.surfaceHigh else Color.Transparent,
                focusedContainer = Colors.focus,
            ) { focused ->
                val ink = if (focused) Colors.onFocus else Colors.ink
                Row(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(option, style = Type.label, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * An on-screen keyboard laid out for the D-pad: letters in alphabetical order on a 7-column grid, so
 * any letter is a few presses away and the layout is the same in English and French.
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
    FocusSurface(onClick, Modifier.size(width, KEY), weight = FocusWeight.Control, corner = KEY / 2, container = Colors.surface, focusedContainer = Colors.focus) { focused ->
        Text(
            label,
            style = Type.label,
            color = if (focused) Colors.onFocus else Colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
