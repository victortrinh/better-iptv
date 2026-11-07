package dev.betteriptv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.PulsingDot
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.SignInCode
import kotlinx.coroutines.delay
import java.net.URI
import java.time.Duration

/** What the Sign-in screen shows (#429). */
sealed interface SignInState {
    /** Asking the cloud for the first code. */
    data object Asking : SignInState

    /** A code to type on the phone, with its QR. */
    data class Showing(val code: SignInCode) : SignInState

    /** The cloud can't give a code right now; it is asked again shortly. */
    data object Unavailable : SignInState

    /** The phone tapped the magic link: the TV is signed in as [email]. */
    data class SignedIn(val email: String) : SignInState
}

/**
 * The Sign-in screen's work while it shows (ADR 0015): asks a code ([start]), then every [POLL] asks
 * whether the phone signed the TV in ([check]), until it has; a code about to expire is replaced, and
 * while the cloud can't give one it is asked again every [RETRY]. Each state goes to [show]; the email
 * signed in as is returned. Cancelled with the screen, it stops polling.
 */
internal suspend fun runSignIn(start: suspend () -> SignInCode?, check: suspend () -> String?, show: (SignInState) -> Unit): String {
    while (true) {
        val code = start()
        if (code == null) {
            show(SignInState.Unavailable)
            delay(RETRY.toMillis())
            continue
        }
        show(SignInState.Showing(code))
        val renewAt = code.expiresIn - RENEW_EARLY
        var waited = Duration.ZERO
        while (waited < renewAt) {
            delay(POLL.toMillis())
            waited += POLL
            check()?.let { email ->
                show(SignInState.SignedIn(email))
                return email
            }
        }
    }
}

/** How often the screen asks whether the TV is signed in (the roadmap's 2 s). */
private val POLL: Duration = Duration.ofSeconds(2)
/** A code is replaced this long before it expires, so the phone never types one that just did. */
private val RENEW_EARLY: Duration = Duration.ofSeconds(15)
/** How long after the cloud couldn't give a code it is asked again. */
private val RETRY: Duration = Duration.ofSeconds(5)

/**
 * Settings → Account → Sign in (#429): the code and its QR, polling while it shows; once the phone
 * signed the TV in, [onSignedIn] returns to Settings, whose row then reads "Signed in as …".
 * "New code" takes first focus.
 */
@Composable
fun SignInScreen(library: Library, onSignedIn: (String) -> Unit) {
    var state by remember { mutableStateOf<SignInState>(SignInState.Asking) }
    var run by remember { mutableStateOf(0) }
    LaunchedEffect(run) {
        onSignedIn(runSignIn(library::startSignIn, library::checkSignIn) { state = it })
    }
    val newCode = remember { FocusRequester() }
    LaunchedEffect(Unit) { newCode.requestFocus() }
    Column(Modifier.padding(horizontal = Spacing.screenX, vertical = 20.dp)) {
        SignInPanel(state)
        Spacer(Modifier.height(Spacing.xl))
        NuageButton(stringResource(R.string.sign_in_new_code), onClick = { run++ }, Modifier.focusRequester(newCode))
    }
}

/**
 * The code and QR for [state], beside what to do with them: a heading ([title]), the steps, the page's
 * address to type when the QR won't scan, and the waiting dot. Reused by the first Sync's card (#438),
 * [compact]: the heading in headline type, the column and QR smaller, so it fits under the checklist.
 */
@Composable
fun SignInPanel(state: SignInState, modifier: Modifier = Modifier, title: String = stringResource(R.string.sign_in_title), compact: Boolean = false) {
    val showing = state as? SignInState.Showing
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(if (compact) CARD_TEXT else PANEL_TEXT)) {
            Text(title, style = if (compact) Type.headline else Type.display, color = Colors.ink)
            Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
            Text(
                stringResource(R.string.sign_in_steps, showing?.code?.url?.let(::typedAddress) ?: ""),
                style = Type.body,
                color = Colors.inkMuted,
            )
            Spacer(Modifier.height(if (compact) Spacing.m else Spacing.xl))
            Text(
                showing?.code?.code?.let(::spaced) ?: "",
                style = Type.display.copy(letterSpacing = Type.display.fontSize * 0.12f),
                color = Colors.ink,
                maxLines = 1,
                modifier = Modifier.testTag("sign-in-code"),
            )
            Spacer(Modifier.height(if (compact) Spacing.s else Spacing.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Signed in, nothing is waited for any more: the line alone.
                if (state !is SignInState.Unavailable && state !is SignInState.SignedIn) {
                    PulsingDot(6.dp)
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(
                    when (state) {
                        SignInState.Unavailable -> stringResource(R.string.sign_in_unavailable)
                        is SignInState.SignedIn -> stringResource(R.string.settings_signed_in_as, state.email)
                        else -> stringResource(R.string.sign_in_waiting)
                    },
                    style = Type.caption,
                    color = if (state is SignInState.Unavailable) Colors.error else Colors.inkMuted,
                )
            }
        }
        Spacer(Modifier.width(if (compact) Spacing.xl else Spacing.xxl))
        val tile = if (compact) CARD_QR_TILE else QR_TILE
        Box(Modifier.size(tile)) {
            showing?.let { Qr(it.code.url, tile, Modifier.testTag("sign-in-qr")) }
        }
    }
}

/**
 * The first Sync's sign-in card (#438), under its checklist: "Sign in to Zugig to keep your history
 * across devices" with the code and QR inline ([SignInPanel], compact) on a `glass` slab with `card`
 * corners. It never takes focus; Back or Home dismisses it ([firstSyncSignIn]).
 */
@Composable
fun SignInCard(state: SignInState, modifier: Modifier = Modifier) {
    Box(modifier.background(Colors.surface, RoundedCornerShape(Corners.card)).padding(CARD_PADDING).testTag(SIGN_IN_CARD_TAG)) {
        SignInPanel(state, title = stringResource(R.string.sign_in_card_title), compact = true)
    }
}

/**
 * Whether the first Sync's sign-in card shows, and what it shows (#438). [offered] is read when the
 * screen opens ([Library.offersSignInCard]: a cloud, no Account, never dismissed or used on this
 * device), and the card shows while [firstSync] lasts. While it shows it polls as the Sign-in screen
 * does ([runSignIn] with [start] and [check]); the state is the screen's, not a slot's, so the code
 * stays the same when the card moves from under the checklist to under the rows. Once the phone signs
 * the TV in it reads "Signed in as …" until the Sync ends. Back dismisses it, and so does Home (the app
 * leaving the front): [onDismiss], and it never comes back on this device; Settings keeps its row.
 */
@Composable
fun firstSyncSignIn(
    offered: Boolean,
    firstSync: Boolean,
    start: suspend () -> SignInCode?,
    check: suspend () -> String?,
    onDismiss: () -> Unit,
): SignInState? {
    var open by rememberSaveable { mutableStateOf(offered) }
    val shown = open && firstSync
    var state by remember { mutableStateOf<SignInState>(SignInState.Asking) }
    val dismiss = {
        open = false
        onDismiss()
    }
    if (shown) {
        LaunchedEffect(Unit) { runSignIn(start, check) { state = it } }
        BackHandler(onBack = dismiss)
        LifecycleEventEffect(Lifecycle.Event.ON_STOP, onEvent = dismiss)
    }
    return state.takeIf { shown }
}

/**
 * [url] as a QR: dark modules on a light tile with a quiet zone, the one light-filled thing besides
 * focus (DESIGN.md, Settings), since many phone cameras won't read a QR drawn light on dark.
 */
@Composable
private fun Qr(url: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(url) { qrMatrix(url) }
    val description = stringResource(R.string.sign_in_qr)
    Canvas(
        modifier.size(size).background(QR_LIGHT, RoundedCornerShape(Corners.card)).semantics { contentDescription = description },
    ) {
        val quiet = QUIET_MODULES
        val cell = this.size.width / (matrix.width + 2 * quiet)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(QR_DARK, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell, cell))
        }
    }
}

/** [url]'s QR modules, with no margin: the tile draws the quiet zone. */
internal fun qrMatrix(url: String): BitMatrix =
    QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))

/** The sign-in page's address as a Viewer would type it: `zugig.app/sign-in`, with a port only when it has one. */
internal fun typedAddress(url: String): String = URI(url).let { it.host + (if (it.port == -1) "" else ":${it.port}") + it.path }

/** A code with a thin gap between its halves, `K7M 2QX`, read more easily from the sofa. */
private fun spaced(code: String) = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

private val PANEL_TEXT = 440.dp
private val QR_TILE = 200.dp
private val CARD_TEXT = 400.dp
private val CARD_QR_TILE = 144.dp
private val CARD_PADDING = 20.dp

const val SIGN_IN_CARD_TAG = "sign-in-card"
/** The quiet zone round the modules the QR standard asks for. */
private const val QUIET_MODULES = 4
private val QR_LIGHT get() = Colors.ink
private val QR_DARK get() = Colors.onFocus
