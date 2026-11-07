package dev.betteriptv.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import dev.betteriptv.app.ui.theme.Colors

/**
 * The app's one sign of work in progress (DESIGN.md, Components): an `ink` disc of [size] that
 * breathes, shrinking to 40% while it fades to 30% alpha and growing back, 700 ms each way. Beside a
 * caption (the Catalog pill), alone as a checklist step's mark, or alone where a screen's rows will go
 * while they are read. Never a spinner, a bar or a shape of what is coming. A fade alone read as a
 * still dot on the Fire TV Stick (#453), above all while a Sync holds back its frames; the change of
 * size is seen even then. Scale and alpha are on the disc's layer alone, read in drawing, so nothing
 * else recomposes or redraws, and the dot keeps its [size] in layout.
 */
@Composable
fun PulsingDot(size: Dp, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breath = pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Box(
        modifier.size(size).graphicsLayer {
            val t = breath.value
            alpha = 1f - (1f - LOW_ALPHA) * t
            scaleX = 1f - (1f - LOW_SCALE) * t
            scaleY = scaleX
        }.background(Colors.ink, CircleShape),
    )
}

/** Each way of the breath: slow enough to read as waiting, quick enough to be seen moving. */
private const val PULSE_MILLIS = 700

/** The dot at its faintest: 30% alpha, 40% of its size. */
private const val LOW_ALPHA = 0.3f
private const val LOW_SCALE = 0.4f
