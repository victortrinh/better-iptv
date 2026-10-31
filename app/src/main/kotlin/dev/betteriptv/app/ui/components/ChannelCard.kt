package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * One Channel: its logo, whole, on a 16:9 glass card, or its name when there is no logo. Its name
 * and the menu key's hint ([menuHint], to add or remove the Favourite) appear under it while it has
 * focus, unless [named] is false (Home, where [RailInfo] tells them). Pressing it calls [onClick];
 * the remote's menu key calls [onMenu].
 */
@Composable
fun ChannelCard(
    logo: Painter?,
    name: String,
    menuHint: String,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    named: Boolean = true,
    onFocus: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(modifier.width(Cards.wideWidth)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).onPreviewKeyEvent { event ->
                if (event.key != Key.Menu) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onMenu()
                true
            },
            container = Colors.surface,
            onFocus = onFocus,
            interactionSource = interaction,
        ) {
            if (logo != null) {
                // Logos come in every shape: shown whole, never cropped.
                Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(Spacing.l))
            } else {
                Text(
                    name,
                    style = Type.label,
                    color = Colors.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.m),
                )
            }
        }
        if (named) {
            // Reserved like a Title card's, so rows don't jump when focus moves.
            Spacer(Modifier.height(12.dp))
            Box(Modifier.height(44.dp)) {
                if (focused) {
                    Column {
                        Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(menuHint, style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
