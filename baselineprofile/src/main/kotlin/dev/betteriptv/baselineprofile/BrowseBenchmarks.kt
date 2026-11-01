package dev.betteriptv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Frame times for the runs in the roadmap's Measurements table (#63): Home's 30 Right then 12 Down,
 * and the Movies tab's 150 Down, from a fresh start (the process just started) and warm (the same
 * run done once already in that process). Each runs with no AOT compilation ([CompilationMode.None],
 * a plain install before Fire OS compiles it) and with the Baseline Profile compiled
 * ([BaselineProfileMode.Require], an install carrying the `.dm`, the updater's path).
 *
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` on a logged-in, synced device
 * ([TARGET_PACKAGE]). Never in CI: it needs a device.
 */
@RunWith(Parameterized::class)
class BrowseBenchmarks(private val compilation: CompilationMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun homeFresh() = measure(fresh = true) { scrollHome() }

    @Test
    fun homeWarm() = measure(fresh = false, warmUp = { scrollHome() }) { scrollHome() }

    @Test
    fun moviesFresh() = measure(fresh = true, before = { openMovies() }) { scrollMovies() }

    @Test
    fun moviesWarm() = measure(fresh = false, warmUp = { openMovies(); scrollMovies() }, before = { openMovies() }) { scrollMovies() }

    /**
     * Measures [run] from Home's first card, after [before]. Fresh, the app starts in a new process
     * just before. Warm, it first starts in a new process and does [warmUp], then is left and
     * started again in that same process, so the JIT has seen the run already.
     */
    private fun measure(
        fresh: Boolean,
        warmUp: MacrobenchmarkScope.() -> Unit = {},
        before: MacrobenchmarkScope.() -> Unit = {},
        run: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = ITERATIONS,
        // Not StartupMode.COLD, which kills the process after the setup, between it and the run.
        startupMode = null,
        setupBlock = {
            killProcess()
            if (!fresh) {
                startHome()
                warmUp()
                pressHome()
            }
            startHome()
            before()
        },
        measureBlock = run,
    )

    private fun MacrobenchmarkScope.startHome() {
        startActivityAndWait(freshActivity())
        waitForRows()
    }

    companion object {
        private const val ITERATIONS = 5

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun compilations() = listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}
