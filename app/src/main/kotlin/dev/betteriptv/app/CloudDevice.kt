package dev.betteriptv.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.betteriptv.core.CloudDevice
import dev.betteriptv.core.DevicePlatform

/**
 * What this device tells the cloud it is when it registers (#570): Fire OS on Amazon's devices, Google TV on
 * any other TV, Android on a phone or tablet, with this build's version.
 */
fun cloudDevice(context: Context): CloudDevice {
    val features = context.packageManager
    val platform = when {
        // A Fire TV has the TV launcher's feature too, so Amazon's is asked first.
        features.hasSystemFeature(FIRE_TV) || Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> DevicePlatform.FireOs
        features.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> DevicePlatform.GoogleTv
        else -> DevicePlatform.Android
    }
    return CloudDevice(platform, BuildConfig.VERSION_NAME)
}

private const val FIRE_TV = "amazon.hardware.fire_tv"
