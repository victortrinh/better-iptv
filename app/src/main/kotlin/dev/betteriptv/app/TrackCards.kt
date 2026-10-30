package dev.betteriptv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * An audio or subtitle Track to pick: its [name], a [detail] under it, and whether it is [on]. Its
 * card names it [short] when it is on, as an audio Track's format would push "Original" out of sight.
 */
internal class TrackOption(val name: String, val on: Boolean, val onClick: () -> Unit, val detail: String? = null, val short: String = name)

/**
 * Audio and subtitles, along the bottom of the picture as four cards, Audio, Subtitles, Subtitle
 * style and Delays, each naming what is on now, so the Viewer sees it all before opening anything.
 * Audio takes first focus; Left and Right move between the cards. OK or Up opens a card's list
 * upward, in the bottom half of the screen only, with focus on what is on; in Audio's and
 * Subtitles' lists Left and Right open the card beside it. Back closes the list, then the cards.
 */
@Composable
internal fun TrackCards(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    style: SubtitleStyle,
    styleNote: String?,
    audioDelay: Long,
    subtitleDelay: Long,
    onStyle: (SubtitleStyle) -> Unit,
    onAudioDelay: (Long) -> Unit,
    onSubtitleDelay: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val none = stringResource(R.string.track_none)
    val subtitleSeconds = String.format(java.util.Locale.getDefault(), "%+.2f", subtitleDelay / 1000.0)
    val audioDelayShown = stringResource(R.string.delay_value, audioDelay)
    val subtitleDelayShown = stringResource(R.string.delay_seconds, subtitleSeconds)
    val cards = listOf(
        TrackCard(stringResource(R.string.tracks_audio), audio.firstOrNull { it.on }?.short ?: none, TRACK_LIST, sideKeys = true) { focus ->
            TrackRows(audio, focus)
        },
        TrackCard(stringResource(R.string.tracks_subtitles), subtitles.firstOrNull { it.on }?.short ?: none, TRACK_LIST, sideKeys = true) { focus ->
            TrackRows(subtitles, focus)
        },
        TrackCard(stringResource(R.string.subtitle_style), styleSummary(style), STYLE_LIST) { focus ->
            if (styleNote != null) Text(styleNote, style = Type.body, color = Colors.inkMuted)
            StyleChoice(R.string.subtitle_size, listOf(R.string.subtitle_small, R.string.subtitle_medium, R.string.subtitle_large, R.string.subtitle_largest), style.size.ordinal, Modifier.focusRequester(focus)) {
                onStyle(style.copy(size = SubtitleSize.entries[it]))
            }
            StyleChoice(R.string.subtitle_colour, listOf(R.string.subtitle_white, R.string.subtitle_yellow), style.colour.ordinal) {
                onStyle(style.copy(colour = SubtitleColour.entries[it]))
            }
            StyleChoice(R.string.subtitle_background, listOf(R.string.subtitle_outline, R.string.subtitle_shadow, R.string.subtitle_box), style.background.ordinal) {
                onStyle(style.copy(background = SubtitleBackground.entries[it]))
            }
            StyleChoice(R.string.subtitle_position, listOf(R.string.subtitle_bottom, R.string.subtitle_raised), style.position.ordinal) {
                onStyle(style.copy(position = SubtitlePosition.entries[it]))
            }
        },
        TrackCard(
            stringResource(R.string.delays),
            listOfNotNull(
                stringResource(R.string.delay_audio_short, audioDelayShown).takeIf { audioDelay != 0L },
                stringResource(R.string.delay_subtitles_short, subtitleDelayShown).takeIf { subtitleDelay != 0L },
            ).joinToString(" · ").ifEmpty { none },
            DELAY_LIST,
        ) { focus ->
            DelayRow(R.string.audio_delay, audioDelay, DELAY_STEP, audioDelayShown, onAudioDelay, Modifier.focusRequester(focus))
            DelayRow(R.string.subtitle_delay, subtitleDelay, SUBTITLE_DELAY_STEP, subtitleDelayShown, onSubtitleDelay)
        },
    )

    val headers = remember { List(cards.size) { FocusRequester() } }
    var open by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { headers[0].requestFocus() }
    BackHandler {
        val shown = open
        if (shown == null) onClose()
        else {
            headers[shown].requestFocus()
            open = null
        }
    }

    Box(Modifier.fillMaxSize().background(bottomScrim)) {
        // The top half stays clear: an open list rises from the cards no higher than the middle of the screen.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.5f)
                .padding(start = Spacing.screenX, end = Spacing.screenX, bottom = Spacing.screenY),
            verticalArrangement = Arrangement.Bottom,
        ) {
            open?.let { index ->
                key(index) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val card = cards[index]
                        val cardWidth = (maxWidth - CARD_GAP * (cards.size - 1)) / cards.size
                        val width = minOf(maxOf(card.listWidth, cardWidth), maxWidth)
                        // Over its own card where it fits, else as far that way as the screen allows.
                        val x = ((cardWidth + CARD_GAP) * index).coerceAtMost(maxWidth - width)
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        var held by remember { mutableStateOf(false) }
                        Column(
                            Modifier.offset(x = x).width(width)
                                // One veil under the whole list, as under the player's Versions.
                                .background(Colors.veil, RoundedCornerShape(Corners.card))
                                .onFocusChanged {
                                    // Focus leaving the list, down to the cards, closes it.
                                    if (held && !it.hasFocus && open == index) open = null
                                    held = it.hasFocus
                                }
                                .onKeyEvent { event ->
                                    if (!card.sideKeys || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> open = (index - 1).coerceAtLeast(0)
                                        Key.DirectionRight -> open = (index + 1).coerceAtMost(cards.size - 1)
                                        else -> return@onKeyEvent false
                                    }
                                    true
                                }
                                .focusGroup()
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.m),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            card.list(this, focus)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.m))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                cards.forEachIndexed { index, card ->
                    CardHeader(
                        card.title,
                        card.value,
                        open = open == index,
                        onOpen = { open = index },
                        modifier = Modifier.weight(1f).focusRequester(headers[index]),
                    )
                }
            }
        }
    }
}

private class TrackCard(
    val title: String,
    val value: String,
    val listWidth: Dp,
    /** Whether Left and Right in its list move to the card beside it; the style's choices use them. */
    val sideKeys: Boolean = false,
    val list: @Composable ColumnScope.(focus: FocusRequester) -> Unit,
)

/** A card's name and what is on now, in glass over the picture; OK or Up opens its list. */
@Composable
private fun CardHeader(title: String, value: String, open: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onOpen,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && !open) {
                onOpen()
                true
            } else false
        },
        weight = FocusWeight.Row,
        corner = Corners.card,
        // Glass carrying text over the picture: the veil first, then glass.
        container = (if (open) Colors.surfaceHigh else Colors.surface).compositeOver(Colors.veil),
        focusedContainer = Colors.focus,
    ) { focused ->
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Text(title, style = Type.caption, color = if (focused) Colors.onFocus.copy(alpha = 0.7f) else Colors.inkMuted, maxLines = 1)
            Text(value, style = Type.label, color = if (focused) Colors.onFocus else Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A list of Tracks, the one on marked "Playing" and taking [focus] (the first, when none is on). */
@Composable
private fun TrackRows(options: List<TrackOption>, focus: FocusRequester) {
    val on = stringResource(R.string.player_playing)
    val focused = options.indexOfFirst { it.on }.coerceAtLeast(0)
    options.forEachIndexed { index, option ->
        SettingRow(
            option.name,
            if (option.on) on else "",
            option.onClick,
            modifier = if (index == focused) Modifier.focusRequester(focus) else Modifier,
            detail = option.detail,
        )
    }
}

/** The style in a few words: its size, colour and background, and its position when raised. */
@Composable
private fun styleSummary(style: SubtitleStyle): String = listOfNotNull(
    stringResource(listOf(R.string.subtitle_small, R.string.subtitle_medium, R.string.subtitle_large, R.string.subtitle_largest)[style.size.ordinal]),
    stringResource(listOf(R.string.subtitle_white, R.string.subtitle_yellow)[style.colour.ordinal]),
    stringResource(listOf(R.string.subtitle_outline, R.string.subtitle_shadow, R.string.subtitle_box)[style.background.ordinal]),
    stringResource(R.string.subtitle_raised).takeIf { style.position == SubtitlePosition.Raised },
).joinToString(", ")

/** A subtitle style setting: its label in a column of its own, the choices beside it. */
@Composable
private fun StyleChoice(label: Int, options: List<Int>, selected: Int, selectedModifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(Modifier.keepsSideFocus(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        ChoiceRow(options.map { stringResource(it) }, selected, onSelect, selectedModifier = selectedModifier)
    }
}

/** An audio or subtitle delay, made earlier or later a step at a time. */
@Composable
private fun DelayRow(label: Int, delay: Long, step: Long, shown: String, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(Modifier.keepsSideFocus(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        NuageButton(stringResource(R.string.delay_earlier), onClick = { onChange(delay - step) }, modifier)
        Text(
            shown,
            style = Type.label,
            color = Colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        NuageButton(stringResource(R.string.delay_later), onClick = { onChange(delay + step) })
    }
}

/** Left and Right stay in this row at its ends, instead of jumping to whatever lies beside it above or below. */
private fun Modifier.keepsSideFocus() = focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}.focusGroup()

private val CARD_GAP = Spacing.m

/** How wide each card's list opens: wide enough for a Track's name, or the style's label column and choices. */
private val TRACK_LIST = 440.dp
private val STYLE_LIST = 620.dp
private val DELAY_LIST = 520.dp

private val STYLE_LABEL = 160.dp

/** How long a delay changes with each press, in milliseconds. */
private const val DELAY_STEP = 50L

/** How far the subtitle delay moves a press: a downloaded subtitle is often off by whole seconds, and finer is unnoticeable. */
private const val SUBTITLE_DELAY_STEP = 250L
