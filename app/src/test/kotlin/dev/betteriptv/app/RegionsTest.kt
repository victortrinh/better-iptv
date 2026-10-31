package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Region list: its Suggested group and its letter rail. */
class RegionsTest {
    @Test
    fun suggestsTheRegionThenTheDeviceCountryThenTheContentLanguagesCountries() {
        assertEquals(
            listOf("DE", "US", "FR", "BE", "CH", "CA"),
            suggestedRegions(region = "DE", deviceCountry = "US", contentLanguages = listOf("fr")),
        )
    }

    @Test
    fun suggestsEachCountryOnce() {
        assertEquals(
            listOf("CA", "FR", "BE", "CH", "US", "GB", "AU", "IE"),
            suggestedRegions(region = "CA", deviceCountry = "CA", contentLanguages = listOf("fr", "en")),
        )
    }

    @Test
    fun suggestsAtMostEight() {
        val suggested = suggestedRegions(region = "JP", deviceCountry = "BR", contentLanguages = listOf("en", "fr", "es", "de"))
        assertEquals(8, suggested.size)
        assertEquals(listOf("JP", "BR", "US", "GB"), suggested.take(4))
    }

    @Test
    fun putsATagsOwnCountryFirst() {
        assertEquals(listOf("CA", "BR", "PT"), suggestedRegions(region = "CA", deviceCountry = "", contentLanguages = listOf("pt-BR")))
    }

    @Test
    fun findsCountriesForALanguageWithoutItsOwnList() {
        val suggested = suggestedRegions(region = "CA", deviceCountry = "", contentLanguages = listOf("sw"))
        assertTrue(suggested.toString(), "TZ" in suggested && "KE" in suggested)
    }

    @Test
    fun skipsWhatIsNotACountry() {
        assertEquals(listOf("CA", "FR", "BE", "CH"), suggestedRegions(region = "CA", deviceCountry = "419", contentLanguages = listOf("fr", "zz")))
    }

    @Test
    fun filesNamesUnderTheirFirstLetterWithoutAccents() {
        assertEquals('E', indexLetter("États-Unis"))
        assertEquals('A', indexLetter("Åland Islands"))
        assertEquals('I', indexLetter("Îles Féroé"))
        assertEquals('C', indexLetter("côte d’Ivoire"))
    }
}
