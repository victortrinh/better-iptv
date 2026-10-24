package dev.betteriptv.app.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val LocalDirection = staticCompositionLocalOf { Directions.Repertoire }

/** The app's tokens, read as `Nuage.colors`, `Nuage.type` and so on. */
object Nuage {
    val direction: Direction
        @Composable @ReadOnlyComposable get() = LocalDirection.current
    val colors: NuageColors
        @Composable @ReadOnlyComposable get() = LocalDirection.current.colors
    val type: NuageType
        @Composable @ReadOnlyComposable get() = LocalDirection.current.type
    val shape: NuageShape
        @Composable @ReadOnlyComposable get() = LocalDirection.current.shape
}

/**
 * Provides [direction] to the app. Both Material themes are mapped from it so stock components
 * (the Compose Material 3 text field in particular) pick up the same colours.
 */
@Composable
fun NuageTheme(direction: Direction = Directions.Repertoire, content: @Composable () -> Unit) {
    val c = direction.colors
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            background = c.ground,
            onBackground = c.ink,
            surface = c.surface,
            onSurface = c.ink,
            onSurfaceVariant = c.inkMuted,
            outline = c.inkMuted,
            error = c.error,
        ),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = c.accent,
                onPrimary = c.onAccent,
                background = c.ground,
                onBackground = c.ink,
                surface = c.surface,
                onSurface = c.ink,
                onSurfaceVariant = c.inkMuted,
                border = c.inkMuted,
                error = c.error,
            ),
        ) {
            @OptIn(ExperimentalFoundationApi::class)
            CompositionLocalProvider(LocalDirection provides direction, LocalBringIntoViewSpec provides EdgeScroll, content = content)
        }
    }
}

/**
 * On TV, Compose scrolls lists to keep the focused item at a fixed pivot, which moves the screen on
 * every press and pushes headers away. Lists here only scroll when focus comes near an edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private object EdgeScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // More room after the item than before it: a card's name and badges sit below its focus area.
        val lead = containerSize * 0.1f
        val trail = containerSize * 0.22f
        val trailing = offset + size
        return when {
            offset >= lead && trailing <= containerSize - trail -> 0f
            offset < lead || size > containerSize - lead - trail -> offset - lead
            else -> trailing - (containerSize - trail)
        }
    }
}
