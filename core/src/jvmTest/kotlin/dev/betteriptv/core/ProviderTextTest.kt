package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Provider's text fields read clean (#473): HTML tags and entities gone, whitespace folded. */
class ProviderTextTest {
    @Test fun `tags, entities and doubled whitespace are stripped from a plot`() {
        assertEquals("Line one. Line two & three's end…", cleanText("<p>Line one.<br/>Line  two &amp; three&#39;s end&hellip;</p>"))
        assertEquals("Ringo ist ein Mann. Er beschließt", cleanText("Ringo ist ein Mann.\r\n\r\nEr beschließt   "))
        assertEquals("A & B", cleanText("A &amp; B"))
        assertEquals("Rock & roll <3", cleanText("Rock & roll <3"))
    }

    @Test fun `every kind of space folds to one, and a field with none comes back as it is`() {
        assertEquals("A B C D E F", cleanText("A\tB\u00A0C\u2007D\u202FE \n F"))
        assertEquals("Al Pacino", cleanText(" Al Pacino"))
        val plain = "Robert De Niro"
        assertTrue(plain === cleanText(plain))
    }

    @Test fun `a field reads the same as when every regex ran on it (#567)`() {
        // cleanText as it was before it skipped the regexes that can't match.
        val tag = Regex("""<[^>]{1,200}>""")
        val entity = Regex("""&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});""")
        val whitespace = Regex("[\\s\u00A0\u2007\u202F]+")
        fun before(raw: String): String? = entity.replace(tag.replace(raw, " ")) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)?.let { codePointString(it) }
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { codePointString(it) }
                else -> mapOf("amp" to "&", "nbsp" to " ", "hellip" to "…")[name.lowercase()]
            } ?: match.value
        }.replace(whitespace, " ").trim().takeIf { it.isNotEmpty() }
        val pieces = listOf("a", "B", "é", " ", "  ", "\t", "\n", "\r", "\u000B", "\u000C", "\u00A0", "\u2007", "\u202F", "\u2003", "<b>", "<", ">", "&amp;", "&nbsp;", "&#39;", "&hellip;", "&", ";", ",")
        val random = Random(567)
        repeat(20_000) {
            val raw = (1..random.nextInt(0, 12)).joinToString("") { pieces.random(random) }
            assertEquals(raw, before(raw), cleanText(raw))
        }
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

    @Test fun `a cast keeps the pieces that read as names (#516)`() {
        val names = listOf(
            "Lou-Pascal Tremblay", "Jean-Pascal Zadi", "Robert De Niro", "J.K. Simmons", "Michael J. Fox", "Clifton Collins Jr.",
            "Patrick St. Esprit", "Dr. Sandra Lee", "Matthijs van de Sande Bakhuyzen", "Óscar de la Fuente", "Noor ul Hasan",
            "Chris 'C.T.' Tamburello", "Seo In-guk", "Rohan roy", "Οσάμα μπιν Λάντεν", "ილია თოფურია", "رحمان باقریان", "Johan H:son Kjellgren",
        )
        assertEquals(names, castNames(names.joinToString(", ")))
    }

    @Test fun `a cast leaves out pieces that read as prose (#516)`() {
        val prose = listOf(
            "Florentina: a charming woman with a lot to offer",
            "but who has never really felt accepted. It doesn't help that she works in her mother's wellness and fitness empire - she is surrounded by color",
            "Hira Khan. Guru",
            "where she talks about her childhood",
            "a veteran actor",
            "reality",
            "Riaan Cruywagen as himself",
            "Mrinal Kulkarni as Godavari Eisha Singh as Kaveri",
            "مجری: مهران مدیری",
            "看黄片网：4488.com",
        )
        assertEquals(listOf("Brian Cox"), castNames((prose + "Brian Cox").joinToString(", ")))
        assertEquals(emptyList<String>(), castNames(null))
    }
}
