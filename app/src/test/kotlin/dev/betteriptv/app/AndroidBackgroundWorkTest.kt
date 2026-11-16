package dev.betteriptv.app

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Android keeps the Core's work alive with a foreground service (#583): it is started as the first
 * piece of work begins, and may stop only once none is left ([AndroidBackgroundWork.running], which
 * [CatalogWorkService] waits on).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidBackgroundWorkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var starts = 0
    private val background by lazy { AndroidBackgroundWork(context) { starts++ } }

    @Test
    fun theServiceIsStartedBeforeTheWorkAndHeldUntilItEnds() = runBlocking {
        val result = background.keepAlive {
            assertEquals(1, starts)
            assertEquals(1, background.running.value)
            "synced"
        }

        assertEquals("synced", result)
        assertEquals(0, background.running.value)
        assertEquals(1, starts)
    }

    @Test
    fun aSyncLeftRunningHoldsTheServiceUntilItFinishes() = runBlocking {
        val leftForAnotherApp = CompletableDeferred<Unit>()
        val sync = async(Dispatchers.Unconfined) { background.keepAlive { leftForAnotherApp.await() } }

        // The Viewer is in another app: the Sync isn't over, and the service has nothing telling it to stop.
        assertEquals(1, starts)
        assertEquals(1, background.running.value)

        leftForAnotherApp.complete(Unit)
        sync.await()
        assertEquals(0, background.running.value)
    }

    @Test
    fun workThatOverlapsSharesOneStartAndTheLastToEndLetsGo() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = async(Dispatchers.Unconfined) { background.keepAlive { first.await() } }
        val b = async(Dispatchers.Unconfined) { background.keepAlive { second.await() } }
        assertEquals(1, starts)
        assertEquals(2, background.running.value)

        first.complete(Unit)
        a.await()
        assertEquals(1, background.running.value)
        second.complete(Unit)
        b.await()
        assertEquals(0, background.running.value)

        // Work that starts once all of it ended starts the service again.
        background.keepAlive { }
        assertEquals(2, starts)
    }

    @Test
    fun workThatFailsLetsGo() = runBlocking {
        val failed = runCatching { background.keepAlive { error("The Provider didn't answer") } }

        assertTrue(failed.isFailure)
        assertEquals(0, background.running.value)
    }
}
