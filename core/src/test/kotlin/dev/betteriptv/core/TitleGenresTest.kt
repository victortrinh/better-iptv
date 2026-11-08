package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Prefix table's genre words name a Title's genres (#473, ADR 0014): from its Category, and from
 * the Provider's genre field in the surveyed languages, folding `Drame`, `Komödie` and `Comedia`
 * into one genre each.
 */
class TitleGenresTest {
    private val table = PrefixTable.base()

    @Test fun `the Provider's genre field in any surveyed language folds into the English names`() {
        assertEquals(listOf("Drama"), table.genres("Drame"))
        assertEquals(listOf("Comedy", "Drama"), table.genres("Komödie / Drama"))
        assertEquals(listOf("Comedy", "Drama"), table.genres("Comedia, Drama"))
        assertEquals(listOf("Comedy", "Drama"), table.genres("Comedy / Drama"))
        assertEquals(listOf("Science Fiction", "Fantasy", "Drama", "Action", "Adventure"), table.genres("Sci-Fi & Fantasy / Drama / Action & Adventure"))
        assertEquals(listOf("Science Fiction", "Fantasy", "Drama", "Action", "Adventure"), table.genres("Science-Fiction & Fantastique / Drame / Action & Adventure"))
        assertEquals(listOf("Science Fiction", "Fantasy", "Drama", "Action", "Adventure"), table.genres("Bilim Kurgu & Fantazi / Dram / Aksiyon & Macera"))
        assertEquals(listOf("Crime", "Mystery"), table.genres("Αστυνομική / Μυστηρίου"))
        assertEquals(listOf("Drama", "Crime"), table.genres("דרמה / פשע"))
        assertEquals(listOf("Crime", "Mystery"), table.genres("Misdaad / Mysterie"))
        assertEquals(listOf("Documentary", "Crime"), table.genres("Documental / Crimen"))
        assertEquals(listOf("Family", "Drama"), table.genres("Familial / Drame"))
    }

    @Test fun `a genre word the table doesn't know is kept as written, and an empty field names none`() {
        assertEquals(listOf("Watch India's Best"), table.genres("Watch India's Best"))
        assertEquals(listOf("Drama", "Kabuki"), table.genres("Drama / Kabuki"))
        assertEquals(emptyList<String>(), table.genres(""))
        assertEquals(emptyList<String>(), table.genres(null))
    }

    @Test fun `a Category's genre words name a Title's genres, but a Channel genre's words say nothing of a Title`() {
        assertEquals(listOf("Drama"), table.titleGenres("EN - DRAMA MOVIES"))
        assertEquals(listOf("Fantasy", "Adventure"), table.titleGenres("FR - FANTASTIQUE AVENTURE"))
        assertEquals(listOf("Comedy"), table.titleGenres("FR - COMEDIE"))
        assertEquals(listOf("Family"), table.titleGenres("EN - KIDS"))
        assertEquals(listOf("Documentary"), table.titleGenres("TR - BELGESEL"))
        assertEquals(emptyList<String>(), table.titleGenres("NETFLIX MOVIES"))
        assertEquals(emptyList<String>(), table.titleGenres("EN - NEW RELEASE"))
    }

    @Test fun `the Channel browser still ranks live Categories by the Channel genres alone`() {
        assertNull(table.genreRank("EN - DRAMA"))
        assertNull(table.genreRank("HISTORY"))
        assertEquals(table.genreRank("MOVIES"), table.genreRank("EN - ACTION MOVIES"))
        assertEquals(table.genreRank("KIDS"), table.genreRank("DE - FAMILIE"))
    }

    @Test fun `a genre is shown in the App language and kept by its English name`() {
        assertEquals("Drame", Genres.named("Drama", "fr-CA"))
        assertEquals("Science-Fiction", Genres.named("Science Fiction", "fr"))
        assertEquals("Drama", Genres.named("Drama", "en"))
        assertEquals("Kabuki", Genres.named("Kabuki", "fr"))
    }
}
