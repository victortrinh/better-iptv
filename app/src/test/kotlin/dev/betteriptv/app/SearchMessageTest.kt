package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Search says where its cards go when it has none to show. */
class SearchMessageTest {
    @Test fun `one letter keeps asking for two instead of saying nothing matches`() {
        // Search needs two letters and finds nothing with one, which is no answer about the catalog (#605).
        assertEquals(SearchMessage.TypeMore, searchMessage("q", searching = false))
        assertEquals(SearchMessage.TypeMore, searchMessage("q", searching = true))
        assertEquals(SearchMessage.TypeMore, searchMessage(" q ", searching = false))
        // Punctuation is no letter.
        assertEquals(SearchMessage.TypeMore, searchMessage("q-", searching = false))
    }

    @Test fun `two letters are searched, and say so when nothing matches`() {
        assertEquals(SearchMessage.Searching, searchMessage("qx", searching = true))
        assertEquals(SearchMessage.NothingMatches, searchMessage("qx", searching = false))
        assertEquals(SearchMessage.NothingMatches, searchMessage("q x", searching = false))
    }

    @Test fun `before anything is typed the query line says what to type, and nothing stands under it`() {
        assertNull(searchMessage("", searching = false))
        assertNull(searchMessage(" ", searching = true))
    }
}
