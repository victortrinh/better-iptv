package dev.betteriptv.baselineprofile

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

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

/**
 * Waits for the rows to be read, on Home and the Movies tab: the featured hero's Play, which the
 * screen opens on (#348), or, with no hero, the Top 10 row's heading. On the stick the hero pushes
 * that heading off screen, so it can't be the only sign.
 */
fun MacrobenchmarkScope.waitForRows() {
    check(waitUntil(ROWS_TIMEOUT) { device.hasObject(By.text(HERO_PLAY)) || device.hasObject(By.textStartsWith("Top movies")) }) {
        "Neither the hero nor a Top movies row: is the app logged in, synced and in English?"
    }
    device.waitForIdle()
}

/**
 * From the featured hero, if focus is on it, Down to the first row's first card, where the runs
 * start. Focus is first let settle below the tabs: a Down from a tab is handled before focus lands
 * on the hero. The first Down scrolls the hero away under the tabs, as the Viewer's would; that is
 * what's checked, as the accessibility tree can go on showing the cards where they were before the
 * scroll, none of them focused.
 */
fun MacrobenchmarkScope.toFirstCard() {
    check(waitUntil(FOCUS_TIMEOUT) { device.hasObject(ON_HERO) || device.hasObject(By.focused(true)) && !device.hasObject(ON_TAB) }) {
        "Focus didn't come down from the tabs; focus is on ${focusedDescription()}"
    }
    if (device.hasObject(ON_HERO)) {
        keys(KeyEvent.KEYCODE_DPAD_DOWN, 1)
        check(waitUntil(FOCUS_TIMEOUT) { !device.hasObject(By.text(HERO_PLAY)) }) { "The hero didn't scroll away; focus is on ${focusedDescription()}" }
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
 * Movies tab, OK on it (only once the Movies tab is what has focus), then, once it is the selected
 * tab and its rows are read, Down past the hero into the rows. Nothing presses OK on the hero,
 * whose Play would open a stream.
 */
fun MacrobenchmarkScope.openMovies() {
    keys(KeyEvent.KEYCODE_BACK, 1)
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
    check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Movies"))), FOCUS_TIMEOUT)) { "The Movies tab didn't take focus" }
    keys(KeyEvent.KEYCODE_DPAD_CENTER, 1)
    // Until the Movies tab is the selected one, Home's hero and rows are still what shows.
    check(device.wait(Until.hasObject(By.selected(true).hasDescendant(By.text("Movies"))), ROWS_TIMEOUT)) { "The Movies tab didn't open" }
    waitForRows()
    // Down from the tab, to the hero when there is one, then on to the first row. A Down pressed
    // while the screen still shows its placeholder rows goes nowhere, so it is pressed again until
    // focus comes down.
    check(waitUntil(ROWS_TIMEOUT) { keys(KeyEvent.KEYCODE_DPAD_DOWN, 1); waitUntil(DOWN_RETRY_MILLIS) { !device.hasObject(ON_TAB) } }) {
        "Focus didn't come down from the Movies tab; focus is on ${focusedDescription()}"
    }
    toFirstCard()
}

/** The Movies tab's run from the Measurements table: [down] presses Down through its rows. */
fun MacrobenchmarkScope.scrollMovies(down: Int = 150) = keys(KeyEvent.KEYCODE_DPAD_DOWN, down)

/** Presses [code] [count] times from one `input` process, each press waiting for the app to handle it. */
fun MacrobenchmarkScope.keys(code: Int, count: Int) {
    device.executeShellCommand("input keyevent " + List(count) { code }.joinToString(" "))
}

/** What has focus, for a failure's message: its bounds and the texts in it. */
private fun MacrobenchmarkScope.focusedDescription(): String {
    val focused = device.findObject(By.focused(true)) ?: return "nothing"
    val texts = focused.findObjects(By.textContains("")).mapNotNull { it.text }.filter { it.isNotEmpty() }
    return "${focused.visibleBounds} $texts"
}

/** Polls [condition] until it holds or [timeout] milliseconds pass; whether it held. */
private fun waitUntil(timeout: Long, condition: () -> Boolean): Boolean {
    val end = SystemClock.uptimeMillis() + timeout
    while (!condition()) {
        if (SystemClock.uptimeMillis() > end) return false
        SystemClock.sleep(POLL_MILLIS)
    }
    return true
}

/** The hero's Play button, by its English label. */
private const val HERO_PLAY = "Play"

/** Focus on one of the hero's buttons, Play or Details. */
private val ON_HERO = By.focused(true).hasDescendant(By.text(Pattern.compile("Play|Details")))

/** Focus on one of the tabs, by their English names. */
private val ON_TAB = By.focused(true).hasDescendant(By.text(Pattern.compile("Home|Movies|Shows|Live")))

private const val ROWS_TIMEOUT = 60_000L
private const val POLL_MILLIS = 100L
private const val DOWN_RETRY_MILLIS = 2_000L
private const val FOCUS_TIMEOUT = 5_000L
