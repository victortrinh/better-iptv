package dev.betteriptv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's own Baseline Profile rules (#63): app start to Home's rows, Right along a row and Down
 * through the rows, then the Movies tab's rows. `./gradlew :app:generateBaselineProfile` runs it
 * on the connected device (Android 13+, or rooted) and writes the rules to
 * `app/src/main/generated/baselineProfiles/baseline-prof.txt`, which release builds ship, in the
 * APK and in the `.dm` the updater installs beside it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun browse() = rule.collect(TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait(freshActivity())
        waitForRows()
        scrollHome(right = 12, down = 8)
        // Home again from its first card, then the Movies tab.
        pressHome()
        startActivityAndWait(freshActivity())
        waitForRows()
        openMovies()
        scrollMovies(down = 20)
    }
}
