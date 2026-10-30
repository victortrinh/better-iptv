package dev.betteriptv.app

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.ApkInstaller
import dev.betteriptv.core.UpdateState
import java.io.File

/**
 * Android's package installer, given a build downloaded into the cache through an install session,
 * which (unlike opening the APK) can carry its dex metadata (#63). Android answers the session at
 * [UpdateInstallReceiver].
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    override fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
        // Fire OS 6 (Android 7.1) has one switch for every app, "Apps from Unknown Sources".
        else @Suppress("DEPRECATION") Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

    override fun install(apk: File, dexMetadata: File?) {
        val installer = context.packageManager.packageInstaller
        // A session the Viewer backed out of, or one left from an earlier try.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            try {
                session.add("base.apk", apk)
                // CI publishes the profile in Android 9–11's format, the stick's (Fire OS 7 is Android 9).
                // Android 7.1 takes only APKs, and Android 12 and later compile from what ProfileInstaller writes.
                if (dexMetadata != null && Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.R) {
                    session.add("base.dm", dexMetadata)
                }
                val answer = Intent(context, UpdateInstallReceiver::class.java)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, answer, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun PackageInstaller.Session.add(name: String, file: File) {
        file.inputStream().use { input ->
            openWrite(name, 0, file.length()).use { output ->
                input.copyTo(output, 64 * 1024)
                fsync(output)
            }
        }
    }
}

/**
 * Android's answers to an update's install session: the confirmation it needs from the Viewer, which
 * opens Android's installer, or why installing failed. Success replaces the running app.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Backed out of the installer: Install opens it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val problem = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android's installer answered $status."
                Log.w(UPDATE_TAG, "Update not installed: $problem")
                (context.applicationContext as BetterIptvApp).updates?.installFailed(problem)
            }
        }
    }
}

private const val UPDATE_TAG = "Updates"

/**
 * The update offered on launch (#21), on the bare ground before anything else: its version, then
 * "Update now" and "Later". Once the Viewer confirms it follows [state]: the first update guides
 * them through allowing this app to install apps, then the download's progress, then Android's
 * installer. [onUpdate] confirms (and carries on once the permission is allowed); [onLater] goes on to the app.
 */
@Composable
fun UpdateScreen(state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    BackHandler(onBack = onLater)
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    // Each step's main button takes focus as it appears.
    LaunchedEffect(state::class) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            when (state) {
                is UpdateState.NeedsPermission -> {
                    // Back from Android's settings: carry on if the Viewer allowed it.
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onUpdate() }
                    Heading(stringResource(R.string.update_allow_title))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Body(stringResource(R.string.update_allow_steps))
                        Buttons {
                            NuageButton(stringResource(R.string.update_open_settings), { openInstallSettings(context) }, Modifier.focusRequester(first))
                            NuageButton(stringResource(R.string.update_later), onLater)
                        }
                        Caption(stringResource(R.string.update_allow_fire))
                    } else {
                        Body(stringResource(R.string.update_allow_old))
                        Buttons {
                            NuageButton(stringResource(R.string.update_open_settings), { openInstallSettings(context) }, Modifier.focusRequester(first))
                            NuageButton(stringResource(R.string.update_later), onLater)
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    Heading(stringResource(R.string.update_title))
                    val fraction = state.downloaded.toFloat() / state.update.size
                    Body(stringResource(R.string.update_downloading, (fraction * 100).toInt()))
                    Progress(fraction)
                    Buttons { NuageButton(stringResource(R.string.update_later), onLater, Modifier.focusRequester(first)) }
                }
                is UpdateState.Installing -> {
                    Heading(stringResource(R.string.update_title))
                    Body(stringResource(R.string.update_installing))
                    Buttons {
                        // Opens the installer again, if the Viewer backed out of it.
                        NuageButton(stringResource(R.string.update_install), onUpdate, Modifier.focusRequester(first))
                        NuageButton(stringResource(R.string.update_later), onLater)
                    }
                }
                is UpdateState.Failed -> {
                    Heading(stringResource(R.string.update_title))
                    Text(stringResource(R.string.update_failed, state.problem), style = Type.body, color = Colors.error, textAlign = TextAlign.Center)
                    Buttons {
                        NuageButton(stringResource(R.string.update_retry), onUpdate, Modifier.focusRequester(first))
                        NuageButton(stringResource(R.string.update_later), onLater)
                    }
                }
                is UpdateState.Available -> {
                    Heading(stringResource(R.string.update_title))
                    Body(stringResource(R.string.update_version, state.update.versionName, BuildConfig.VERSION_NAME))
                    Buttons {
                        NuageButton(stringResource(R.string.update_now), onUpdate, Modifier.focusRequester(first))
                        NuageButton(stringResource(R.string.update_later), onLater)
                    }
                }
                UpdateState.Unknown, UpdateState.UpToDate -> Unit
            }
        }
    }
}

/**
 * Android's page for allowing this app to install apps (Android 8 and later), or on Fire OS 6 the
 * developer options that hold "Apps from Unknown Sources". The screen's text says where to go if
 * neither opens.
 */
private fun openInstallSettings(context: Context) {
    val pages = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
        add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        add(Intent(Settings.ACTION_SETTINGS))
    }
    for (page in pages) {
        try {
            context.startActivity(page)
            return
        } catch (e: ActivityNotFoundException) {
            // The next page, then.
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = Type.headline, color = Colors.ink, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) = Text(text, style = Type.body, color = Colors.inkMuted, textAlign = TextAlign.Center)

@Composable
private fun Caption(text: String) = Text(text, style = Type.caption, color = Colors.inkMuted, textAlign = TextAlign.Center)

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) { content() }
}

/** The download's progress: white over white at 25%, like the player's scrubber. */
@Composable
private fun Progress(fraction: Float) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().height(6.dp).background(Colors.ink.copy(alpha = 0.25f), shape)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(Colors.ink, shape))
    }
}
