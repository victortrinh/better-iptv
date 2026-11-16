package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.Character.UnicodeScript

/** What a text's letters say of its language, where the Prefix is wrong ([Scripts]). */
class ScriptsTest {
    @Test fun `a text is in the script most of its letters are in`() {
        assertEquals(UnicodeScript.LATIN, Scripts.of("Deux amies échouent."))
        assertEquals(UnicodeScript.GREEK, Scripts.of("Δύο φίλες ναυαγούν στο Aegean."))
        assertEquals(UnicodeScript.LATIN, Scripts.of("The Godfather الأب"))
        assertNull(Scripts.of("1917 (2019)"))
    }

    @Test fun `a Viewer reads their App language's script and Latin letters`() {
        assertTrue(Scripts.reads("en", "Two friends are stranded."))
        assertTrue(Scripts.reads("fr-CA", "Deux amies échouent."))
        assertFalse(Scripts.reads("en", "Δύο φίλες ναυαγούν."))
        assertTrue(Scripts.reads("el", "Δύο φίλες ναυαγούν."))
        assertTrue(Scripts.reads("el", "Two friends are stranded."))
        assertFalse(Scripts.reads("en", ""))
        assertFalse(Scripts.reads("en", null))
    }

    @Test fun `letters of another script overrule the Prefix, Latin letters never do`() {
        assertEquals("el", Scripts.written("en", { "Η Ταινία" }))
        assertEquals("el", Scripts.written(null, { "Film" }, { "Δύο φίλες ναυαγούν." }))
        assertEquals("el", Scripts.written("el", { "Verity" }))
        assertEquals("ru", Scripts.written("ru", { "Фильм" }))
        // Cyrillic names no one language.
        assertEquals(Scripts.UNDETERMINED, Scripts.written("en", { "Фильм" }))
        assertEquals("ja", Scripts.written("ja", { "映画" }))
        assertEquals("en", Scripts.written("en", { null }, { "A plot." }))
        assertNull(Scripts.written(null, { "Film" }))
    }
}
