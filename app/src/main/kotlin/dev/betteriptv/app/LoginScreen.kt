package dev.betteriptv.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.betteriptv.core.Expired
import dev.betteriptv.core.Library
import dev.betteriptv.core.LoggedIn
import dev.betteriptv.core.ProviderLogin
import dev.betteriptv.core.ProviderProblem
import dev.betteriptv.core.Unreachable
import dev.betteriptv.core.WrongLogin
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Takes the Provider login with the remote and the on-screen keyboard. */
@Composable
fun LoginScreen(library: Library, onLoggedIn: () -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val focus = LocalFocusManager.current
    val canLogIn = !checking && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val logIn = {
        checking = true
        problem = null
        scope.launch {
            when (val result = library.logIn(ProviderLogin(server.trim(), username.trim(), password))) {
                is LoggedIn -> onLoggedIn()
                is ProviderProblem -> problem = context.describe(result)
            }
            checking = false
        }
        Unit
    }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        LoginField(server, { server = it }, R.string.login_server, placeholder = R.string.login_server_hint, keyboardType = KeyboardType.Uri)
        LoginField(username, { username = it }, R.string.login_username)
        LoginField(
            password,
            { password = it },
            R.string.login_password,
            keyboardType = KeyboardType.Password,
            onDone = { if (canLogIn) logIn() else focus.moveFocus(FocusDirection.Down) },
        )
        Button(enabled = canLogIn, onClick = logIn) {
            Text(stringResource(if (checking) R.string.login_checking else R.string.login_button))
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * A text field the remote can leave: D-pad up and down move focus instead of the cursor, and the
 * keyboard's action key moves on to the next field (or runs [onDone] on the last one).
 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes placeholder: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(stringResource(label)) },
        placeholder = placeholder?.let { { androidx.compose.material3.Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { onDone?.invoke() }),
        modifier = Modifier.width(520.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                else -> false
            }
        },
    )
}

fun Context.describe(problem: ProviderProblem): String = when (problem) {
    WrongLogin -> getString(R.string.login_wrong)
    is Expired -> problem.expiredOn?.let { getString(R.string.login_expired_on, longDate.format(it)) }
        ?: getString(R.string.login_expired)
    is Unreachable -> getString(R.string.provider_unreachable, problem.detail)
}

private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(ZoneId.systemDefault())
