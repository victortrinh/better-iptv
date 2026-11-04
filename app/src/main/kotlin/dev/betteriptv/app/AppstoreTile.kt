package dev.betteriptv.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Redraws Zugig's tile in Fire OS's Your Apps after an update. The Amazon Appstore saves a sideloaded
 * app's icon once, at install, and updates keep that copy (see "A stale icon in Your Apps" in
 * docs/releases.md). Telling the Appstore the app was removed, then added, makes it delete the copy
 * and save the installed icon. The tile keeps its place, but shows the new icon only once the
 * Appstore's cache is cleared and it's force-stopped, which only the Viewer can do.
 *
 * Measured on Fire OS 7, Appstore 8.5103 (2026-11-03). It leans on the Appstore's undocumented
 * receiver: if a later Appstore drops it, the broadcasts go nowhere and the tile stays as it was.
 */
class AppstoreTileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) refreshAppstoreTile(context)
    }
}

/** Sends the Appstore the pair of broadcasts, if this is Fire OS; elsewhere, nothing. */
fun refreshAppstoreTile(context: Context) {
    if (!hasAppstore(context)) return
    try {
        appstoreTileIntents(context.packageName).forEach(context::sendBroadcast)
    } catch (e: RuntimeException) {
        Log.w("AppstoreTile", "The Appstore's tile wasn't refreshed", e)
    }
}

/**
 * Removed, then added, sent only to the Appstore receiver that saves the icon, so its other
 * listeners never hear of a removal (the tile's place and the library entry stay). Without
 * android.intent.extra.REPLACING: with it, the Appstore leaves the icon alone.
 */
internal fun appstoreTileIntents(packageName: String): List<Intent> =
    listOf(ACTION_REMOVED, ACTION_ADDED).map { action ->
        Intent(action, Uri.fromParts("package", packageName, null)).setComponent(APPSTORE_RECEIVER)
    }

private fun hasAppstore(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo(APPSTORE_RECEIVER.packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

private const val ACTION_REMOVED = "com.amazon.tv.action.PACKAGE_REMOVED"
private const val ACTION_ADDED = "com.amazon.tv.action.PACKAGE_ADDED"
private val APPSTORE_RECEIVER = ComponentName("com.amazon.venezia", "com.amazon.mas.client.apps.AppManagerBroadcastReceiver")
