package dev.betteriptv.baselineprofile

import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The app these drive: the debug build's, which the `nonMinifiedRelease` and `benchmarkRelease`
 * builds install over, keeping its login and catalog. It must be logged in and synced first, in
 * English, and on Home; nothing here logs in, and nothing presses OK on a card, so no stream opens.
 * `-Pandroid.testInstrumentationRunnerArguments.targetPackage=…` points them at another build.
 */
val TARGET_PACKAGE: String =
    InstrumentationRegistry.getArguments().getString("targetPackage") ?: "dev.betteriptv.debug"

/**
 * The app's launch intent in a new task, so a process already running starts a new activity on Home's
 * first card, not the one left scrolled down the rows.
 */
fun MacrobenchmarkScope.freshActivity(): Intent =
    checkNotNull(InstrumentationRegistry.getInstrumentation().context.packageManager.getLaunchIntentForPackage(packageName)) { "$packageName isn't installed" }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** Waits for the rows to be read: the Top 10 row's heading, on Home and the Movies tab. */
fun MacrobenchmarkScope.waitForRows() {
    check(device.wait(Until.hasObject(By.textStartsWith("Top movies")), ROWS_TIMEOUT)) {
        "No Top movies row: is the app logged in, synced and in English?"
    }
    device.waitForIdle()
}

/**
 * Home's run from the roadmap's Measurements table: [right] presses Right along the first row,
 * then [down] presses Down through the rows, from one `input` process that waits for each key to
 * be handled, as the stick's key repeat waits for the app.
 */
fun MacrobenchmarkScope.scrollHome(right: Int = 30, down: Int = 12) {
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, right)
    keys(KeyEvent.KEYCODE_DPAD_DOWN, down)
}

/**
 * From Home's first card to the Movies tab's first card: Back to the Home tab, Right to the
 * Movies tab, OK on it (only once the Movies tab is what has focus), then Down into the rows.
 */
fun MacrobenchmarkScope.openMovies() {
    keys(KeyEvent.KEYCODE_BACK, 1)
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
    check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Movies"))), FOCUS_TIMEOUT)) { "The Movies tab didn't take focus" }
    keys(KeyEvent.KEYCODE_DPAD_CENTER, 1)
    waitForRows()
    keys(KeyEvent.KEYCODE_DPAD_DOWN, 1)
    device.waitForIdle()
}

/** The Movies tab's run from the Measurements table: [down] presses Down through its rows. */
fun MacrobenchmarkScope.scrollMovies(down: Int = 150) = keys(KeyEvent.KEYCODE_DPAD_DOWN, down)

/** Presses [code] [count] times from one `input` process, each press waiting for the app to handle it. */
fun MacrobenchmarkScope.keys(code: Int, count: Int) {
    device.executeShellCommand("input keyevent " + List(count) { code }.joinToString(" "))
}

private const val ROWS_TIMEOUT = 60_000L
private const val FOCUS_TIMEOUT = 5_000L
