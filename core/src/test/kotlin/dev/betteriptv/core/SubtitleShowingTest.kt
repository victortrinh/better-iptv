package dev.betteriptv.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which downloaded subtitle shows, as the Viewer picks them and the Track choice asks for one. */
class SubtitleShowingTest {
    private val best = SubtitleOffer("opensubtitles:en:1", "en", "Movie.2025.1080p.WEB", false)
    private val netflix = SubtitleOffer("subdl:en:2", "en", "Movie.2025.1080p.NF.WEB-DL", false)
    private val third = SubtitleOffer("opensubtitles:en:3", "en", "Movie.2025.720p.BluRay", false)

    /** Downloads that finish only when the test says so, each with the offer's release as its text. */
    private val pending = HashMap<String, CompletableDeferred<String?>>()
    private val showing = SubtitleShowing { offer: SubtitleOffer -> pending.getOrPut(offer.id) { CompletableDeferred() }.await() }

    private fun finish(offer: SubtitleOffer, text: String? = offer.release) = pending.getOrPut(offer.id) { CompletableDeferred() }.complete(text)

    @Test fun `the Viewer's pick stands when the file shown by itself finishes downloading after it`() = runBlocking {
        // The best file starts downloading as the Movie starts; the Viewer picks another before it is in.
        val byItself = async { showing.showFirst(listOf(best)) }
        yield()
        val picked = async { showing.show(netflix) }
        yield()
        finish(netflix)
        assertTrue(picked.await())
        finish(best)
        assertFalse(byItself.await())

        assertEquals(netflix, showing.state.value.shown)
        assertEquals(netflix.release, showing.state.value.file)
    }

    @Test fun `a file that won't download is noted, and the best in a language falls to the next`() = runBlocking {
        finish(best, null)
        finish(netflix)
        assertTrue(showing.showFirst(listOf(best, netflix, third)))

        assertEquals(netflix, showing.state.value.shown)
        assertEquals(setOf(best.id), showing.state.value.failed)
        assertNull(showing.state.value.loading)
    }

    @Test fun `a file that won't read counts as failed, not as a crash`() = runBlocking {
        val broken = SubtitleShowing<String> { error("WEBVTT header missing") }
        assertFalse(broken.show(best))
        assertEquals(setOf(best.id), broken.state.value.failed)
        assertNull(broken.state.value.shown)
    }

    @Test fun `the Viewer's pick of a failed file tries it again`() = runBlocking {
        var tries = 0
        val flaky = SubtitleShowing { _: SubtitleOffer -> if (tries++ == 0) null else "text" }
        assertFalse(flaky.show(best))
        // The best in the language passes over it...
        assertFalse(flaky.showFirst(listOf(best)))
        assertEquals(1, tries)
        // ...but a pick asks again, and once it comes it is no longer noted as failed.
        assertTrue(flaky.show(best))
        assertEquals(emptySet<String>(), flaky.state.value.failed)
    }

    @Test fun `while a file downloads its offer is noted as loading, and turning subtitles off drops it`() = runBlocking {
        val picked = async { showing.show(netflix) }
        yield()
        assertEquals(netflix, showing.state.value.loading)
        showing.hide()
        finish(netflix)
        assertFalse(picked.await())
        assertNull(showing.state.value.shown)
        assertNull(showing.state.value.loading)
    }

    @Test fun `a file downloaded once shows again without downloading`() = runBlocking {
        var downloads = 0
        val counting = SubtitleShowing { offer: SubtitleOffer -> downloads++; offer.release }
        counting.show(best)
        counting.show(netflix)
        counting.show(best)
        assertEquals(2, downloads)
        assertEquals(best, counting.state.value.shown)
    }
}
