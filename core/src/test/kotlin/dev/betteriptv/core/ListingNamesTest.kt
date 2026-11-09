package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Title's card names and pictures it from the Provider's Listings (#474, ADR 0014), seen through
 * the Library over the fake Provider: the App language's Listing names it, else the English one,
 * else the first. Its poster, the same for every Viewer first, is the Listing's in the Title's
 * original language (Wikidata's), else the English one's, else a streaming service Listing's, and
 * only then the App language Listing's, a Content language Listing's (in the Viewer's order), the
 * French one's, else the file most Listings share, ties to the Best playable Version's; a poster
 * under 300 px wide is refused for the next one (#526). Its page tells of it from the same Listing:
 * the original language's, else the English one.
 */
class ListingNamesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val film = TitleId.Numbered(TitleKind.Movie, 4242)
    private val every = listOf("en", "fr", "de", "el", "tr")

    private fun json(text: String?) = if (text == null) "\"\"" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Serves these movie Listings of Title 4242, each `name to poster`, numbered from 1 in one Category, and Syncs. */
    private suspend fun syncFilm(vararg listings: Pair<String, String?>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":${json(listing.first)},"tmdb":"4242","category_id":"1","stream_icon":${json(listing.second)},""" +
                """"added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        library.sync()
    }

    private suspend fun card(appLanguage: String, contentLanguages: List<String> = every) =
        library.titles(0, 10, contentLanguages, appLanguage).single { it.id == film }

    @Test fun aTitleIsNamedByItsListingInTheAppLanguageElseTheEnglishOne() = runBlocking {
        syncFilm("EN - Film (2024)" to null, "FR - Le Film (2024)" to null)

        assertEquals("Le Film", card("fr").name)
        assertEquals("Film", card("en").name)
        assertEquals("Film", card("de").name)
        assertEquals(2024, card("fr").year)
        assertEquals("Le Film", library.title(film, every, "fr")!!.name)
    }

    @Test fun aListingIsNamedInTheLanguageItsPrefixSaysEvenWhenItsFileTurnsOutToBeInEnglish() = runBlocking {
        // The surveyed Provider's Forgotten Island (#504): the `ES` copy's file has English audio, so a
        // read moves it to English, where it is the best Version; its name is still the Spanish one.
        syncFilm("EN - Forgotten Island (2026)" to "https://art/en.jpg", "ES-4K - La isla olvidada (2026)" to "https://art/es.jpg")
        val spanishCopy = library.title(film, listOf("en"), "en")!!.let { it.languages + it.otherLanguages }.flatMap { it.versions }.single { it.listingId == 2L }
        library.confirm(spanishCopy, FileContents(listOf(Track("eng", "audio/eac3", 6)), emptyList(), width = 3840, height = 2160))

        val page = library.title(film, listOf("en"), "en")!!
        assertEquals(listOf(2L, 1L), page.languages.single { it.language == "en" }.versions.map { it.listingId })
        assertEquals("Forgotten Island", card("en", listOf("en")).name)
        assertEquals("Forgotten Island", page.name)
        assertEquals("https://art/en.jpg", page.image)
        assertEquals("La isla olvidada", library.title(film, listOf("en", "es"), "es")!!.name)
    }

    /** Forgotten Island (#504): the `ES` copy's file has English audio, so once read it is the best English Version. */
    private suspend fun syncForgottenIsland(originalLanguage: String? = null) {
        originalLanguage?.let { cloud.titleFacts(film, originalLanguage = it) }
        syncFilm("EN - Forgotten Island (2026)" to null, "ES-4K - La isla olvidada (2026)" to null)
        provider.serveMovieDetails(1, plot = "Two friends are stranded.", cast = "Ana", director = "Joe", name = "Forgotten Island")
        provider.serveMovieDetails(2, plot = "Dos amigas se quedan varadas.", cast = "Ana (voz)", director = "José", name = "La isla olvidada")
        val spanishCopy = library.title(film, listOf("en"), "en")!!.let { it.languages + it.otherLanguages }.flatMap { it.versions }.single { it.listingId == 2L }
        library.confirm(spanishCopy, FileContents(listOf(Track("eng", "audio/eac3", 6)), emptyList(), width = 3840, height = 2160))
    }

    @Test fun theTitlePageTellsOfATitleInEnglishWhateverTheViewersLanguagesWhenItsOriginalLanguageIsUnknown() = runBlocking {
        // #504: the page read the best Version's details only, the `ES` copy's, and told of the Title in Spanish.
        syncForgottenIsland()

        val page = library.title(film, listOf("en"), "en")!!
        assertEquals(2L, page.languages.first().versions.first().listingId)
        assertEquals("Two friends are stranded.", page.description)
        assertEquals(listOf("Ana"), page.cast)
        assertEquals(listOf("Joe"), page.directors)
        // A Spanish Viewer's page is named in Spanish and told of in English, there being no original language to go by.
        val spanish = library.title(film, listOf("en", "es"), "es")!!
        assertEquals("La isla olvidada", spanish.name)
        assertEquals("Two friends are stranded.", spanish.description)
    }

    @Test fun theTitlePageTellsOfATitleInItsOriginalLanguageForEveryViewer() = runBlocking {
        // Wikidata says the film is Spanish: its own plot, under its English name, for an English Viewer.
        syncForgottenIsland(originalLanguage = "es")

        val page = library.title(film, listOf("en"), "en")!!
        assertEquals("Forgotten Island", page.name)
        assertEquals("Dos amigas se quedan varadas.", page.description)
        assertEquals(listOf("Ana (voz)"), page.cast)
        assertEquals("Dos amigas se quedan varadas.", library.title(film, listOf("en", "es"), "es")!!.description)
    }

    @Test fun aTitleWithOnlyAGreekListingIsNamedByIt() = runBlocking {
        syncFilm("GR - Η Ταινία (2024)" to null)

        assertEquals("Η Ταινία", card("fr").name)
        assertEquals(2024, card("fr").year)
    }

    /** Survey B (#526): each Prefix language mostly links a poster of its own, and the Spanish one is often the file most Listings share. */
    private suspend fun syncSurveyB() = syncFilm(
        "ES - La Película (2024)" to "https://art/es.jpg", "LAT - La Película (2024)" to "https://art/es.jpg",
        "IT - Il Film (2024)" to "https://art/es.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg",
        "EN - Film (2024)" to "https://art/en.jpg",
    )

    @Test fun everyViewerSeesThePosterInTheTitlesOriginalLanguage() = runBlocking {
        // Wikidata says the film is French: its own one-sheet, whatever the Viewer reads.
        cloud.titleFacts(film, originalLanguage = "fr")
        syncSurveyB()

        assertEquals("https://art/fr.jpg", card("fr").image)
        assertEquals("https://art/fr.jpg", card("en").image)
        assertEquals("https://art/fr.jpg", card("tr", listOf("en")).image)
    }

    @Test fun everyViewerSeesTheEnglishPosterWhenNoListingIsInTheOriginalLanguageOrItIsUnknown() = runBlocking {
        syncSurveyB()

        assertEquals("https://art/en.jpg", card("fr").image)
        assertEquals("https://art/en.jpg", card("en").image)
        assertEquals("https://art/en.jpg", card("tr", listOf("fr", "en")).image)
        assertEquals(listOf("https://art/en.jpg", "https://art/fr.jpg", "https://art/es.jpg"), card("fr", listOf("fr")).posters)
    }

    @Test fun anOriginalLanguageNoListingIsInGivesWayToTheEnglishPoster() = runBlocking {
        cloud.titleFacts(film, originalLanguage = "ko")
        syncSurveyB()

        assertEquals("https://art/en.jpg", card("fr").image)
    }

    @Test fun anEnglishViewerSeesTheFrenchPosterWhereNoEnglishListingHasOne() = runBlocking {
        // The surveyed Provider's Verity (2026): FR, GR and IR Listings, each its own one-sheet. An
        // English Viewer whose Content languages leave French out saw the Greek one.
        syncFilm(
            "GR - Verity (2026)" to "https://art/gr.jpg", "IR - Verity (2026)" to "https://art/ir.jpg",
            "FR - Verity (2026)" to "https://art/fr.jpg",
        )

        assertEquals("https://art/fr.jpg", card("en", listOf("en")).image)
        assertEquals("https://art/gr.jpg", card("el", listOf("el")).image)
    }

    @Test fun aTitleWithNoListingInTheViewersLanguagesShowsTheFileMostListingsShare() = runBlocking {
        syncFilm("ES - La Película (2024)" to "https://art/es.jpg", "IT - Il Film (2024)" to "https://art/es.jpg", "DE - Der Film (2024)" to "https://art/de.jpg")

        assertEquals("https://art/es.jpg", card("tr", listOf("tr")).image)
    }

    @Test fun aStreamingServiceListingsPosterStandsForTheEnglishOneBeforeTheViewersLanguagesAndTheSharedFile() = runBlocking {
        // The artwork survey: Titles with no EN or FR Listing showed the Spanish, Greek or German file
        // most Listings share, while their Netflix, Disney+ or Prime copy (a Source, no language) links
        // the English one-sheet.
        syncFilm(
            "DE - Der Film (2024)" to "https://art/de.jpg", "GR - Η Ταινία (2024)" to "https://art/de.jpg",
            "ES - La Película (2024)" to "https://art/de.jpg", "4K-NF - Film (2024)" to "https://art/nf.jpg",
            "SC - Film (2024)" to "https://art/sc.jpg",
        )

        assertEquals("https://art/nf.jpg", card("en", listOf("en", "fr")).image)
        assertEquals("https://art/nf.jpg", card("de").image)
        assertEquals(listOf("https://art/nf.jpg", "https://art/de.jpg", "https://art/sc.jpg"), card("de", listOf("de")).posters)
    }

    @Test fun theOriginalLanguagesPosterComesBeforeAStreamingServiceListings() = runBlocking {
        cloud.titleFacts(film, originalLanguage = "de")
        syncFilm("DE - Der Film (2024)" to "https://art/de.jpg", "4K-NF - Film (2024)" to "https://art/nf.jpg", "EN - Film (2024)" to "https://art/en.jpg")

        assertEquals(listOf("https://art/de.jpg", "https://art/en.jpg", "https://art/nf.jpg"), card("en", listOf("en")).posters)
    }

    @Test fun theCardKeepsTheRestOfThePostersInOrderForWhenOneIsRefusedAsItDecodes() = runBlocking {
        cloud.titleFacts(film, originalLanguage = "de")
        syncFilm(
            "ES - La Película (2024)" to "https://art/es.jpg", "IT - Il Film (2024)" to "https://art/es.jpg",
            "DE - Der Film (2024)" to "https://art/de.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg",
            "EN - Film (2024)" to "https://art/en.jpg",
        )

        // The German one (the original), the English one, then the Viewer's: French, then the shared file.
        assertEquals(
            listOf("https://art/de.jpg", "https://art/en.jpg", "https://art/fr.jpg", "https://art/es.jpg"),
            card("fr", listOf("fr", "de", "en")).posters,
        )
    }

    @Test fun aPosterTmdbNamesUnder300PxWideIsRefusedForTheNextOne() = runBlocking {
        // 13% of the surveyed Provider's show posters are 154 × 231 and blur at card size.
        syncFilm(
            "EN - Film (2024)" to "https://image.tmdb.org/t/p/w154/en.jpg", "FR - Le Film (2024)" to "https://image.tmdb.org/t/p/w185/fr.jpg",
            "DE - Der Film (2024)" to "https://image.tmdb.org/t/p/w220_and_h330_face/de.jpg",
            "IT - Il Film (2024)" to "https://image.tmdb.org/t/p/w500/it.jpg",
        )

        assertEquals("https://image.tmdb.org/t/p/w500/it.jpg", card("en").image)
        assertEquals(listOf("https://image.tmdb.org/t/p/w500/it.jpg"), card("fr").posters)
    }

    @Test fun postersTmdbNamesAt300PxOrItsOriginalAreKept() = runBlocking {
        syncFilm(
            "EN - Film (2024)" to "https://image.tmdb.org/t/p/w300/en.jpg", "FR - Le Film (2024)" to "https://image.tmdb.org/t/p/original/fr.jpg",
            "DE - Der Film (2024)" to "https://image.tmdb.org/t/p/w600_and_h900_bestv2/de.jpg",
        )

        assertEquals("https://image.tmdb.org/t/p/w300/en.jpg", card("en").image)
        assertEquals(
            listOf("https://image.tmdb.org/t/p/w300/en.jpg", "https://image.tmdb.org/t/p/original/fr.jpg", "https://image.tmdb.org/t/p/w600_and_h900_bestv2/de.jpg"),
            card("fr").posters,
        )
        assertEquals(
            listOf("https://image.tmdb.org/t/p/w300/en.jpg", "https://image.tmdb.org/t/p/w600_and_h900_bestv2/de.jpg", "https://image.tmdb.org/t/p/original/fr.jpg"),
            card("de").posters,
        )
    }

    @Test fun aTitleWhosePostersAreAllRefusedHasNone() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://image.tmdb.org/t/p/w154/en.jpg", "FR - Le Film (2024)" to "https://image.tmdb.org/t/p/w154/fr.jpg")

        assertNull(card("en").image)
        assertEquals(emptyList<String>(), card("en").posters)
        assertNull(library.title(film, every, "en")!!.image)
    }

    @Test fun aFileCountsOncePerLanguageSoACopyListedTwiceDoesNotOutvoteTheOthers() = runBlocking {
        // The surveyed Provider lists `DE - Der Astronaut: Project Hail Mary` twice with one file.
        syncFilm(
            "FR - Le Film (2024)" to "https://art/fr.jpg", "IT - Il Film (2024)" to "https://art/fr.jpg",
            "DE - Der Film (2024)" to "https://art/de.jpg", "DE - Der Film (2024)" to "https://art/de.jpg",
        )

        // A Turkish Viewer, with no Listing in their languages: the file two languages share, not the one listed twice.
        assertEquals("https://art/fr.jpg", card("tr", listOf("tr")).image)
        assertEquals("https://art/de.jpg", card("de", listOf("de")).image)
    }

    @Test fun withOnePosterEachTheEnglishListingWinsForEveryViewerThenTheAppLanguages() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg")

        assertEquals("https://art/en.jpg", card("fr").image)
        assertEquals("https://art/en.jpg", card("de").image)
        assertEquals("https://art/en.jpg", card("es").image)
        assertEquals(listOf("https://art/en.jpg", "https://art/de.jpg", "https://art/fr.jpg"), card("de", listOf("de")).posters)
    }

    @Test fun withoutTheAppLanguageTheContentLanguagesPostersGoInTheViewersOrder() = runBlocking {
        syncFilm("DE - Der Film (2024)" to "https://art/de.jpg", "FR-4K - Le Film (2024)" to "https://art/fr.jpg")

        assertEquals("https://art/de.jpg", card("es", listOf("de", "fr")).image)
        assertEquals("https://art/fr.jpg", card("es", listOf("fr", "de")).image)
    }

    @Test fun withinALanguageTheBestPlayableVersionsPosterWins() = runBlocking {
        syncFilm("FR - Le Film (2024)" to "https://art/fr.jpg", "FR-4K - Le Film (2024)" to "https://art/fr4k.jpg")

        assertEquals("https://art/fr4k.jpg", card("fr").image)
    }

    @Test fun aListingAddedInAnotherLanguageChangesNoPoster() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg")
        val before = card("fr").image

        syncFilm(
            "EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg",
            "TR - Film (2024)" to "https://art/tr.jpg",
        )

        assertEquals(before, card("fr").image)
        assertEquals("https://art/en.jpg", card("fr").image)
    }

    @Test fun aTitleWhoseListingsHaveNoPosterHasNone() = runBlocking {
        syncFilm("EN - Film (2024)" to null, "FR - Le Film (2024)" to null)

        assertNull(card("en").image)
    }
}
