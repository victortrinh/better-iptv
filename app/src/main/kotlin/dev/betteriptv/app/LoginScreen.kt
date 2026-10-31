package dev.betteriptv.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.NuageTextField
import dev.betteriptv.app.ui.components.Wordmark
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
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
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val focus = LocalFocusManager.current
    val canLogIn = !checking && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val logIn = {
        checking = true
        problem = null
        scope.launch {
            when (val result = library.logIn(ProviderLogin(address.trim(), username.trim(), password))) {
                is LoggedIn -> onLoggedIn()
                is ProviderProblem -> problem = context.describe(result)
            }
            checking = false
        }
        Unit
    }

    Row(
        Modifier.fillMaxSize().background(Colors.ground).padding(horizontal = Spacing.screenX * 2, vertical = Spacing.screenY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.xxl)) {
            Wordmark(style = Type.display.copy(fontSize = 38.sp, lineHeight = 40.sp))
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.login_title), style = Type.headline, color = Colors.ink)
            Spacer(Modifier.height(Spacing.s))
            Text(stringResource(R.string.login_private), style = Type.body, color = Colors.inkMuted)
        }
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            val fieldModifier = Modifier.fillMaxWidth()
            NuageTextField(
                address, { address = it }, stringResource(R.string.login_address), fieldModifier,
                placeholder = stringResource(R.string.login_address_hint), keyboardType = KeyboardType.Uri,
            )
            NuageTextField(username, { username = it }, stringResource(R.string.login_username), fieldModifier)
            NuageTextField(
                password, { password = it }, stringResource(R.string.login_password), fieldModifier,
                keyboardType = KeyboardType.Password,
                onDone = { if (canLogIn) logIn() else focus.moveFocus(FocusDirection.Down) },
            )
            Spacer(Modifier.height(Spacing.xs))
            NuageButton(stringResource(if (checking) R.string.login_checking else R.string.login_button), logIn, enabled = canLogIn)
            problem?.let { Text(it, style = Type.body, color = Colors.error) }
        }
    }
}

fun Context.describe(problem: ProviderProblem): String = when (problem) {
    WrongLogin -> getString(R.string.login_wrong)
    is Expired -> problem.expiredOn?.let { getString(R.string.login_expired_on, longDate.format(it)) }
        ?: getString(R.string.login_expired)
    is Unreachable -> getString(R.string.provider_unreachable, problem.detail)
}

private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(ZoneId.systemDefault())
