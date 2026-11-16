package dev.betteriptv.app

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.AccountDevice
import dev.betteriptv.core.AccountDevicesContent
import dev.betteriptv.core.AccountDevicesScreen
import dev.betteriptv.core.Library
import kotlinx.coroutines.launch
import kotlin.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.toJavaInstant

/** What the Account section opens in place of the Settings column (#429, #434). */
enum class AccountPage {
    /** The Sign-in code and QR. */
    SignIn,

    /** The question before signing this TV out. */
    SignOut,

    /** The devices signed in to the Account. */
    Devices,
}

/**
 * The Account section's rows (ADR 0015): "Sign in" while signed out; signed in, "Signed in as …",
 * which offers Sign out, and "Devices", which lists the Account's TVs. Read from the device alone:
 * a device that never signs in never asks the cloud about an Account.
 */
@Composable
fun AccountRows(library: Library, open: (AccountPage) -> Unit, accountRow: Modifier, devicesRow: Modifier) {
    val account by library.account.collectAsStateWithLifecycle()
    AccountRows(account, open, accountRow, devicesRow)
}

/** [AccountRows] for the Account signed in as [email], or none. */
@Composable
internal fun AccountRows(email: String?, open: (AccountPage) -> Unit, accountRow: Modifier, devicesRow: Modifier) {
    if (email == null) {
        // Back from the device list after signing this TV out lands here: the Devices row is gone.
        SettingRow(stringResource(R.string.settings_sign_in), "", onClick = { open(AccountPage.SignIn) }, accountRow.then(devicesRow), detail = stringResource(R.string.settings_sign_in_detail))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(stringResource(R.string.settings_signed_in_as, email), stringResource(R.string.account_sign_out), onClick = { open(AccountPage.SignOut) }, accountRow)
        SettingRow(
            stringResource(R.string.account_devices),
            "",
            onClick = { open(AccountPage.Devices) },
            devicesRow,
            detail = stringResource(R.string.account_devices_detail),
        )
    }
}

/** The [page] the Account section opened; [onDone] returns to the Settings column. */
@Composable
fun AccountPageContent(library: Library, page: AccountPage, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (page) {
        AccountPage.SignIn -> SignInScreen(library, onSignedIn = { onDone() })
        AccountPage.SignOut -> SignOutPage(
            stringResource(R.string.account_sign_out_this_confirm),
            stringResource(R.string.account_sign_out_this_detail),
            onSignOut = {
                scope.launch {
                    library.signOut()
                    onDone()
                }
            },
            onCancel = onDone,
        )
        AccountPage.Devices -> DevicesPage(library, onDone)
    }
}

/**
 * Settings → Account → Devices (#434): each TV signed in to the Account by its model and sign-in
 * date, this one marked "This TV", in the order they signed in, as the Core lists them
 * ([AccountDevicesContent]); "Asking…" until it has. OK on one asks before signing it out
 * ([SignOutPage]); this one goes anonymous at once and Settings returns, another at its next cloud
 * contact while the list asks the cloud again. The first row takes first focus.
 */
@Composable
private fun DevicesPage(library: Library, onDone: () -> Unit) {
    var asked by remember { mutableStateOf(0) }
    // Null while the cloud is asked the first time; a list asked again stays until its answer.
    var list by remember { mutableStateOf<AccountDevicesContent?>(null) }
    var picked by remember { mutableStateOf<AccountDevice?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(library, asked, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { library.screen(AccountDevicesScreen).collect { list = it } }
    }
    val device = picked
    if (device != null) {
        BackHandler { picked = null }
        SignOutPage(
            if (device.thisDevice) stringResource(R.string.account_sign_out_this_confirm) else stringResource(R.string.account_sign_out_confirm, deviceName(device)),
            if (device.thisDevice) stringResource(R.string.account_sign_out_this_detail) else stringResource(R.string.account_sign_out_other_detail),
            onSignOut = {
                scope.launch {
                    val out = library.signOut(device)
                    picked = null
                    if (device.thisDevice) {
                        onDone()
                    } else {
                        failed = !out
                        asked++
                    }
                }
            },
            onCancel = { picked = null },
        )
        return
    }
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    SettingsColumn {
        item { SettingsHeading(R.string.account_devices) }
        if (failed) item { Text(stringResource(R.string.account_unavailable), style = Type.body, color = Colors.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        when (val shown = list) {
            null -> item { Text(stringResource(R.string.account_devices_asking), style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            AccountDevicesContent.Unavailable -> {
                item { Text(stringResource(R.string.account_unavailable), style = Type.body, color = Colors.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                item { SettingRow(stringResource(R.string.account_try_again), "", onClick = { asked++ }, Modifier.focusRequester(first)) }
            }
            is AccountDevicesContent.Listed -> shown.devices.forEachIndexed { index, it ->
                item(key = it.id) {
                    SettingRow(
                        deviceName(it),
                        if (it.thisDevice) stringResource(R.string.account_this_tv) else "",
                        onClick = { picked = it },
                        if (index == 0) Modifier.focusRequester(first) else Modifier,
                        detail = stringResource(R.string.account_signed_in_on, signInDate(it.signedInAt, locale)),
                    )
                }
            }
        }
    }
    LaunchedEffect(list) { if (list != null) runCatching { first.requestFocus() } }
}

/** The question before signing a TV out: [title] and [detail], then Cancel, which takes first focus, and Sign out. */
@Composable
private fun SignOutPage(title: String, detail: String, onSignOut: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocus() }
    SettingsColumn {
        item { SettingsHeading(title) }
        item { Text(detail, style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        item { SettingRow(stringResource(R.string.profile_cancel), "", onClick = onCancel, Modifier.focusRequester(cancel)) }
        item { SettingRow(stringResource(R.string.account_sign_out), "", onClick = onSignOut) }
    }
}

/** A listed device's name: its model, or "A TV" when it sent none. */
@Composable
private fun deviceName(device: AccountDevice) = device.model ?: stringResource(R.string.account_unnamed_tv)

/** When a device signed in, as a long date in the App language: "November 3, 2026". */
internal fun signInDate(at: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).withZone(ZoneId.systemDefault()).format(at.toJavaInstant())

/** This device's model as the device list names it, sent with the Sign-in code and nowhere else. */
fun deviceModel(): String = deviceModelName(Build.MANUFACTURER, Build.MODEL)

/**
 * A device's model by its [manufacturer] and [model] code: a Fire TV by Amazon's name for it
 * ("AFTKA" is "Fire TV Stick 4K Max"), any other as its maker and model ("NVIDIA SHIELD Android TV").
 */
internal fun deviceModelName(manufacturer: String, model: String): String =
    FIRE_TV_MODELS[model] ?: listOf(manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) }, model)
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
        .joinToString(" ")
        .ifBlank { "TV" }

/** Amazon's names for its Fire TV model codes (developer.amazon.com, Fire TV device specifications). */
private val FIRE_TV_MODELS = mapOf(
    "AFTM" to "Fire TV Stick",
    "AFTT" to "Fire TV Stick (2nd Gen)",
    "AFTSSS" to "Fire TV Stick (3rd Gen)",
    "AFTSS" to "Fire TV Stick Lite",
    "AFTMM" to "Fire TV Stick 4K",
    "AFTKRT" to "Fire TV Stick 4K (2nd Gen)",
    "AFTKA" to "Fire TV Stick 4K Max",
    "AFTKM" to "Fire TV Stick 4K Max (2nd Gen)",
    "AFTA" to "Fire TV Cube",
    "AFTR" to "Fire TV Cube (2nd Gen)",
    "AFTGAZL" to "Fire TV Cube (3rd Gen)",
)
