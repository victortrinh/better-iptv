package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import kotlinx.coroutines.awaitCancellation

/**
 * A glass text field with its label above it, for a remote. Focus alone doesn't open the on-screen
 * keyboard, which would take the D-pad and cover the fields under it: D-pad up and down move
 * between fields, and OK opens the keyboard. Its action key moves on to the next field with the
 * keyboard still open (or closes it and runs [onDone] on the last one); Back closes it and stays on
 * the field.
 *
 * [typing] is whether the keyboard is open. A form shares one between its fields, so the action
 * key carries the keyboard from a field to the next.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NuageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    typing: MutableState<Boolean> = remember { mutableStateOf(false) },
    onDone: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corners.card)
    val close = {
        typing.value = false
        keyboard?.hide()
    }

    Column(modifier) {
        Text(label, style = Type.caption, color = if (focused) Colors.ink else Colors.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        InterceptPlatformTextInput(keyboardOnlyWhile(typing)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body.copy(color = Colors.ink),
                cursorBrush = SolidColor(Colors.ink),
                visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                    onDone = {
                        close()
                        onDone?.invoke()
                    },
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
                    // Back reaches the keyboard before the app, and the keyboard closes without a word.
                    .onPreInterceptKeyBeforeSoftKeyboard { event ->
                        if (event.key != Key.Back || !typing.value) return@onPreInterceptKeyBeforeSoftKeyboard false
                        if (event.type == KeyEventType.KeyUp) close()
                        true
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { close(); focus.moveFocus(FocusDirection.Down) }
                            Key.DirectionUp -> { close(); focus.moveFocus(FocusDirection.Up) }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                typing.value = true
                                keyboard?.show()
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .background(if (focused) Colors.surfaceHigh else Colors.surface, shape)
                            .then(if (focused) Modifier.border(2.dp, Colors.focus, shape) else Modifier)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = Type.body, color = Colors.inkMuted)
                        field()
                    }
                },
            )
        }
    }
}

/**
 * A text field asks the system for its keyboard as soon as it has focus. Until [typing], the
 * request goes nowhere. A new interceptor each time [typing] changes makes the field ask again; it
 * reads [typing] as it is asked, since focus can reach the next field before this is composed again.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun keyboardOnlyWhile(typing: MutableState<Boolean>): PlatformTextInputInterceptor = remember(typing, typing.value) {
    PlatformTextInputInterceptor { request, next -> if (typing.value) next.startInputMethod(request) else awaitCancellation() }
}
