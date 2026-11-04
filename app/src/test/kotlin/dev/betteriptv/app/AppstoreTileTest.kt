package dev.betteriptv.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** After an update, the Appstore hears the app was removed then added, only on Fire OS. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppstoreTileTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun anUpdateTellsTheAppstoreRemovedThenAdded() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.amazon.venezia" })

        AppstoreTileReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val sent = shadowOf(context).broadcastIntents
        assertEquals(listOf("com.amazon.tv.action.PACKAGE_REMOVED", "com.amazon.tv.action.PACKAGE_ADDED"), sent.map { it.action })
        sent.forEach {
            assertEquals("package:${context.packageName}", it.dataString)
            assertEquals("com.amazon.venezia/com.amazon.mas.client.apps.AppManagerBroadcastReceiver", it.component?.flattenToString())
            // With REPLACING the Appstore keeps its old copy of the icon.
            assertTrue(!it.hasExtra(Intent.EXTRA_REPLACING))
        }
    }

    @Test
    fun withoutTheAppstoreNothingIsSent() {
        AppstoreTileReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(shadowOf(context).broadcastIntents.isEmpty())
    }
}
