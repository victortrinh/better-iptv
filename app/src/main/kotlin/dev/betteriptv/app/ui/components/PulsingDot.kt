package dev.betteriptv.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import dev.betteriptv.app.ui.theme.Colors

/**
 * The app's one sign of work in progress (DESIGN.md, Components): an `ink` disc of [size] pulsing
 * slowly between full and 30% alpha. Beside a caption (the Catalog pill), alone as a checklist step's
 * mark, or alone where a screen's rows will go while they are read. Never a spinner, a bar or a
 * shape of what is coming. The pulse is alpha on the disc alone, so nothing else redraws.
 */
@Composable
fun PulsingDot(size: Dp, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.size(size).graphicsLayer { this.alpha = alpha }.background(Colors.ink, CircleShape))
}

/**
 * One cycle of [PulsingDot]'s rhythm, for something that is noticed once rather than waited on (the
 * profile chip, #438): while [pulse], the alpha to draw goes from full to 30% and back, [PULSE_MILLIS]
 * each way, linear, then [onDone] is called. Read the alpha in a `graphicsLayer`, so nothing redraws.
 */
@Composable
fun pulseOnce(pulse: Boolean, onDone: () -> Unit): () -> Float {
    val alpha = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(pulse) {
        if (!pulse) return@LaunchedEffect
        alpha.animateTo(0.3f, tween(PULSE_MILLIS, easing = LinearEasing))
        alpha.animateTo(1f, tween(PULSE_MILLIS, easing = LinearEasing))
        done()
    }
    return { alpha.value }
}

/** Each way of the pulse: slow enough to read as waiting, not activity. */
private const val PULSE_MILLIS = 900
