package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Provider's text fields read clean (#473): HTML tags and entities gone, whitespace folded. */
class ProviderTextTest {
    @Test fun `tags, entities and doubled whitespace are stripped from a plot`() {
        assertEquals("Line one. Line two & three's end…", cleanText("<p>Line one.<br/>Line  two &amp; three&#39;s end&hellip;</p>"))
        assertEquals("Ringo ist ein Mann. Er beschließt", cleanText("Ringo ist ein Mann.\r\n\r\nEr beschließt   "))
        assertEquals("A & B", cleanText("A &amp; B"))
        assertEquals("Rock & roll <3", cleanText("Rock & roll <3"))
    }

    @Test fun `an empty or blank field is nothing`() {
        assertNull(cleanText(null))
        assertNull(cleanText(""))
        assertNull(cleanText("  \n "))
        assertNull(cleanText("<br>"))
    }

    @Test fun `a comma-separated list of names reads as a list, each once`() {
        assertEquals(listOf("Al Pacino", "Robert De Niro"), cleanList("Al Pacino,  Robert De Niro, Al Pacino"))
        assertEquals(emptyList<String>(), cleanList(""))
        assertEquals(emptyList<String>(), cleanList(null))
    }
}
