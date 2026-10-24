package dev.betteriptv.app.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Wraps the app. The TV Material theme is mapped from the tokens so stock components match. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NuageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Colors.focus,
            onPrimary = Colors.onFocus,
            background = Colors.ground,
            onBackground = Colors.ink,
            surface = Colors.ground,
            onSurface = Colors.ink,
            onSurfaceVariant = Colors.inkMuted,
            border = Colors.inkMuted,
            error = Colors.error,
        ),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeScroll, content = content)
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
