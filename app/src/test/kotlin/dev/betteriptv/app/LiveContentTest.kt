package dev.betteriptv.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen's content from the Core, as a screen that reads what it shows holds it ([LiveContent],
 * #587): an entry waits for the first content, each change tells the screen to read again, and the
 * same content is never read twice for one change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveContentTest {
    /** The Core's content, standing in for [dev.betteriptv.core.Library.screen]: what it holds now, and how many times it was read. */
    private val core = MutableStateFlow<List<String>?>(null)
    private var reads = 0
    private val content = LiveContent(coreContent())

    private fun coreContent() = kotlinx.coroutines.flow.flow {
        core.collect { rows -> if (rows != null) emit(rows) }
    }.onEach { reads++ }

    @Test
    fun anEntryWaitsForTheFirstContentAndReadsItOnce() = runTest(UnconfinedTestDispatcher()) {
        val following = launch { content.collect() }
        val entry = async(start = CoroutineStart.UNDISPATCHED) { content.read() }
        assertFalse(entry.isCompleted)

        core.value = listOf("new")

        assertEquals(listOf("new"), entry.await())
        assertEquals(0, content.changes)
        assertEquals(1, reads)
        following.cancel()
    }

    @Test
    fun contentThatChangesTellsTheScreenToReadAgainAndIsReadWithoutAskingTheCore() = runTest(UnconfinedTestDispatcher()) {
        core.value = listOf("new")
        val following = launch { content.collect() }
        content.read()

        core.value = listOf("continue", "new")

        assertEquals(1, content.changes)
        val before = reads
        assertEquals(listOf("continue", "new"), content.read())
        assertEquals(before, reads)
        following.cancel()
    }

    @Test
    fun aScreenThatReadsForAnotherReasonAsksTheCoreNow() = runTest(UnconfinedTestDispatcher()) {
        core.value = listOf("new")
        val following = launch { content.collect() }
        content.read()
        val before = reads

        // The screen reads again by itself (a Favourite it just saved): nothing waits unread, so the Core is asked.
        assertEquals(listOf("new"), content.read())

        assertTrue(reads > before)
        assertEquals(0, content.changes)
        following.cancel()
    }

    @Test
    fun theSameContentHandedOverAgainIsNoChange() = runTest(UnconfinedTestDispatcher()) {
        core.value = listOf("new")
        val following = launch { content.collect() }
        content.read()
        following.cancel()

        // Followed again, as on coming back from another app: the Core hands over what the screen holds.
        val again = launch { content.collect() }

        assertEquals(0, content.changes)
        again.cancel()
    }
}
