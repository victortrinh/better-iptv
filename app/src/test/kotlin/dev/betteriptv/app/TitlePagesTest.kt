package dev.betteriptv.app

import androidx.compose.runtime.snapshots.Snapshot
import dev.betteriptv.app.TitlePages.Companion.PAGE_SIZE
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePagesTest {
    private val scope = TestScope()
    private val reads = mutableListOf<Int>()
    private val pages = TitlePages(scope) { offset, limit ->
        reads += offset / PAGE_SIZE
        List(limit) { offset + it }
    }

    @Test
    fun readsThePagesShowingAndTheOneAhead() {
        pages.showing(first = 0, last = 20, count = 10_000)
        scope.runCurrent()

        assertEquals(listOf(0, 1), reads)
        assertEquals(5, pages[5])
        assertEquals(70, pages[70])
        assertNull(pages[2 * PAGE_SIZE])
    }

    @Test
    fun readsThePageBehindToo() {
        pages.showing(first = 3 * PAGE_SIZE + 5, last = 3 * PAGE_SIZE + 30, count = 10_000)
        scope.runCurrent()

        assertEquals(listOf(3, 4, 2), reads)
    }

    @Test
    fun readsAcrossAPageBoundaryInView() {
        pages.showing(first = PAGE_SIZE - 7, last = PAGE_SIZE + 14, count = 10_000)
        scope.runCurrent()

        assertEquals(listOf(0, 1, 2), reads)
    }

    @Test
    fun staysInsideTheList() {
        pages.showing(first = 70, last = 99, count = 100)
        scope.runCurrent()

        assertEquals(listOf(1, 0), reads)
    }

    @Test
    fun readsEachPageOnce() {
        pages.showing(first = 0, last = 20, count = 10_000)
        pages.showing(first = 7, last = 27, count = 10_000)
        scope.runCurrent()
        pages.showing(first = 14, last = 34, count = 10_000)
        scope.runCurrent()

        assertEquals(listOf(0, 1), reads)
    }

    @Test
    fun aPageArrivingRedrawsOnlyItsOwnCards() {
        pages.showing(first = 0, last = 20, count = 10_000)
        scope.runCurrent()
        // What a card on page 0 and a still blank card on page 2 read in composition.
        val readOnPage0 = mutableSetOf<Any>()
        val readOnPage2 = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { readOnPage0 += it }) { pages[5] }
        Snapshot.observe(readObserver = { readOnPage2 += it }) { pages[2 * PAGE_SIZE + 5] }
        Snapshot.sendApplyNotifications()
        val changed = mutableSetOf<Any>()
        val observer = Snapshot.registerApplyObserver { objects, _ -> changed.addAll(objects) }

        pages.showing(first = PAGE_SIZE + 20, last = PAGE_SIZE + 40, count = 10_000)
        scope.runCurrent()
        Snapshot.sendApplyNotifications()
        observer.dispose()

        assertEquals(listOf(0, 1, 2), reads)
        assertTrue("the card on page 2 is redrawn", (readOnPage2 intersect changed).isNotEmpty())
        assertTrue("the card on page 0 isn't", (readOnPage0 intersect changed).isEmpty())
    }

    @Test
    fun keepsThePagesNearestWhereTheViewerIs() {
        for (page in 0..15) {
            pages.showing(first = page * PAGE_SIZE, last = page * PAGE_SIZE + 20, count = 10_000)
            scope.runCurrent()
        }

        // At page 15, with page 16 read ahead.
        assertEquals((7..16).toSet(), pages.kept)
        assertNull(pages[0])
    }
}
