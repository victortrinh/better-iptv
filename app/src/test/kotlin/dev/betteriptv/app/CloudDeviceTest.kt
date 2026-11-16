package dev.betteriptv.app

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/** What the app tells the cloud it runs on when it registers (#570). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CloudDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aFireTvIsFireOs() {
        // A Fire TV has Android TV's launcher feature too.
        shadowOf(context.packageManager).setSystemFeature("amazon.hardware.fire_tv", true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)

        assertEquals(DevicePlatform.FireOs, cloudDevice(context).platform)
    }

    @Test
    fun anAmazonTabletIsFireOs() {
        ShadowBuild.setManufacturer("Amazon")

        assertEquals(DevicePlatform.FireOs, cloudDevice(context).platform)
    }

    @Test
    fun anotherTvIsGoogleTv() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)

        assertEquals(DevicePlatform.GoogleTv, cloudDevice(context).platform)
    }

    @Test
    fun aPhoneIsAndroid() {
        assertEquals(DevicePlatform.Android, cloudDevice(context).platform)
    }

    @Test
    fun theVersionIsTheBuilds() {
        assertEquals(BuildConfig.VERSION_NAME, cloudDevice(context).appVersion)
    }
}
