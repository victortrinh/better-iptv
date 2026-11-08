package dev.betteriptv.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Work that holds the Viewer's one stream runs only while the app is on screen (#503). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnScreenTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val lifecycle = owner.lifecycle
    private val dreaming = MutableStateFlow(false)
    private val onScreen = onScreen(lifecycle, dreaming)

    /** Counts the runs of a block that holds on until cancelled, and its cancellations. */
    private class Held {
        var runs = 0
        var cancelled = 0
        suspend fun hold() {
            runs++
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
    }

    @Test
    fun leavingTheAppCancelsTheWorkAndComingBackRunsItAgain() = runTest {
        lifecycle.currentState = Lifecycle.State.RESUMED
        val held = Held()
        val work = async { onScreen.whileTrue { held.hold() } }
        runCurrent()
        assertEquals(1, held.runs)

        // Home pressed, or the TV asleep: the activity is stopped, not destroyed.
        lifecycle.currentState = Lifecycle.State.CREATED
        runCurrent()
        assertEquals(1, held.cancelled)

        lifecycle.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(2, held.runs)
        work.cancel()
    }

    @Test
    fun aScreensaverOverTheAppCancelsTheWorkThoughTheActivityStaysStarted() = runTest {
        lifecycle.currentState = Lifecycle.State.RESUMED
        val held = Held()
        val work = async { onScreen.whileTrue { held.hold() } }
        runCurrent()

        dreaming.value = true
        runCurrent()
        assertEquals(1, held.cancelled)

        dreaming.value = false
        runCurrent()
        assertEquals(2, held.runs)
        work.cancel()
    }

    @Test
    fun aPauseAloneLeavesTheWorkRunning() = runTest {
        lifecycle.currentState = Lifecycle.State.RESUMED
        val held = Held()
        val work = async { onScreen.whileTrue { held.hold() } }
        runCurrent()

        // A system overlay, such as Fire OS's Alexa panel, pauses the activity without stopping it.
        lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(0, held.cancelled)
        work.cancel()
    }

    @Test
    fun nothingRunsUntilTheAppIsOnScreen() = runTest {
        lifecycle.currentState = Lifecycle.State.CREATED
        var ran = false
        val work = async { onScreen.whileTrue { ran = true } }
        runCurrent()
        assertFalse(ran)

        lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        work.await()
        assertTrue(ran)
    }

    @Test
    fun workThatFinishesReturnsItsResultAndIsNotRunAgain() = runTest {
        lifecycle.currentState = Lifecycle.State.RESUMED
        var runs = 0
        val done = CompletableDeferred<Unit>()
        val work = async { onScreen.whileTrue { runs++; done.await(); "ended" } }
        runCurrent()
        done.complete(Unit)
        assertEquals("ended", work.await())

        lifecycle.currentState = Lifecycle.State.CREATED
        lifecycle.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(1, runs)
    }
}
