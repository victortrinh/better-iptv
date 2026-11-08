package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Bidi

/**
 * The metadata line (year · runtime · genres · rating) lays out left to right in an English UI,
 * whatever text the Provider's genres hold (#514): a right-to-left genre stays in its slot and
 * doesn't carry the rating along with it.
 */
class MetaLineTest {
    /** Whether every character of [part] in [line] lays out left to right, in a left-to-right paragraph. */
    private fun leftToRight(line: String, part: String): Boolean {
        val bidi = Bidi(line, Bidi.DIRECTION_LEFT_TO_RIGHT)
        val start = line.indexOf(part)
        assertTrue("$part in $line", start >= 0)
        return (start until start + part.length).all { bidi.getLevelAt(it) % 2 == 0 }
    }

    @Test fun `a right-to-left genre doesn't reorder the rating after it`() {
        val line = metaLine("2024", genresPart(listOf("Drama", "קומדיה")), ratingLabel(4.0))

        assertTrue(leftToRight(line, ratingLabel(4.0)))
        assertTrue(leftToRight(line, "2024"))
        // The parts keep their order in the text: year, genres, rating.
        assertTrue(line.indexOf("2024") < line.indexOf("קומדיה") && line.indexOf("קומדיה") < line.indexOf("★"))
    }

    @Test fun `a line of left-to-right parts reads as before, and what's missing is left out`() {
        assertEquals("2024 · Drama, Comedy · ★ 6.6", metaLine("2024", genresPart(listOf("Drama", "Comedy", "Crime")), ratingLabel(6.6)))
        assertEquals("2024 · ★ 6.6", metaLine("2024", genresPart(emptyList()), ratingLabel(6.6)))
        assertEquals("2024 · Drama · ★ 6.6", aboutLine(2024, listOf("Drama"), 6.6))
    }

    @Test fun `the rail info's line keeps its rating left to right after a right-to-left genre`() {
        val line = aboutLine(2026, listOf("Κινούμενα Σχέδια"), 8.0)
        assertTrue(leftToRight(line, ratingLabel(8.0)))
        val hebrew = aboutLine(2024, listOf("קומדיה"), 4.0)
        assertTrue(leftToRight(hebrew, ratingLabel(4.0)))
        assertTrue(leftToRight(hebrew, "2024"))
    }
}
