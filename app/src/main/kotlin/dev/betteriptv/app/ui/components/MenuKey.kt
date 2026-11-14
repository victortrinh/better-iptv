package dev.betteriptv.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the remote has a menu key (≡). A Fire TV's does; a Google TV's has none, and neither do
 * most Android TV remotes, so there holding OK does what the menu key does ([menuKey]) and the
 * hints say so.
 */
fun hasMenuKey(context: Context): Boolean = context.packageManager.hasSystemFeature(FIRE_TV)

/** [hasMenuKey], for the screen's hints. */
@Composable
fun rememberHasMenuKey(): Boolean {
    val context = LocalContext.current
    return remember(context) { hasMenuKey(context) }
}

/**
 * The remote's second action on a surface whose OK is [onClick]: the menu key calls [onMenu], and
 * so does holding OK, on every remote, for the ones without a menu key. OK let go before it repeats
 * is the click, as it is without this; a held OK calls [onMenu] once and no click follows. Goes
 * before the surface's own click handling, which then never sees OK.
 */
@Composable
fun Modifier.menuKey(onClick: () -> Unit, onMenu: () -> Unit): Modifier {
    val ok = remember { OkPress() }
    return onPreviewKeyEvent { event ->
        when (ok.on(event.type, event.key, event.nativeKeyEvent.repeatCount)) {
            OkPress.Does.Click -> onClick()
            OkPress.Does.Menu -> onMenu()
            OkPress.Does.Nothing -> Unit
            null -> return@onPreviewKeyEvent false
        }
        true
    }
}

/** Tells a press of OK from a hold, and reads the menu key, for [menuKey]. */
internal class OkPress {
    enum class Does { Click, Menu, Nothing }

    /** OK went down here: its release elsewhere (a press that brought focus here) is no click. */
    private var down = false
    private var held = false

    /** What the key event does, or null when it isn't the menu key's or OK's. */
    fun on(type: KeyEventType, key: Key, repeats: Int): Does? = when (key) {
        Key.Menu -> if (type == KeyEventType.KeyUp) Does.Menu else Does.Nothing
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when (type) {
            KeyEventType.KeyDown -> when {
                repeats == 0 -> {
                    down = true
                    held = false
                    Does.Nothing
                }
                down && !held -> {
                    held = true
                    Does.Menu
                }
                else -> Does.Nothing
            }
            KeyEventType.KeyUp -> {
                val click = down && !held
                down = false
                held = false
                if (click) Does.Click else Does.Nothing
            }
            else -> null
        }
        else -> null
    }
}

private const val FIRE_TV = "amazon.hardware.fire_tv"
