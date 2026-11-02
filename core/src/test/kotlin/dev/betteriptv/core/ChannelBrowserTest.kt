package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Channel browser through the Library after a Sync (#259): the countries the Channels row
 * lists and their order, each country's Categories with their counts and merged names, and each
 * Category's Channels. Now and next on the Channels is a later ticket (#261).
 */
class ChannelBrowserTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    /** How a Provider names the start of a Game [ago] before now, in its `(2026-10-29 18:00:30)` style. */
    private fun startedAgo(ago: Duration) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((Instant.now() - ago).atOffset(ZoneOffset.UTC))

    /** A country's Categories as "name · count". */
    private suspend fun categories(country: String) = library.browsedCategories(country).map { "${it.name} · ${it.channels}" }

    /** A Category's Channels by key, as the browser lists them. */
    private suspend fun channels(country: String, category: String) =
        library.categoryChannels(library.browsedCategories(country).first { it.name == category }).map { it.key }

    @Test fun `the countries come from the Category names, the Region first, then the Content languages' countries, then by Channel count`() = runBlocking {
        provider.serveChannels(
            "DE: ARD" to "DE| GENERAL", "DE: ZDF" to "DE| GENERAL", "DE: RTL" to "DE| GENERAL", "DE: SAT 1" to "DE| GENERAL",
            "US: NBC" to "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "US: CBS" to "US| CBS", "US: ABC" to "US| ABC",
            "UK: BBC ONE" to "UK| GENERAL", "UK: BBC TWO" to "UK| GENERAL",
            "CA FR: TVA" to "CA FR| GÉNÉRAL", "CA EN: CBC" to "CA EN| GENERAL",
            "FR: TF1" to "FR| GENERAL",
            "NL: NPO 1" to "NL| GENERAL", "NL: NPO 2" to "NL| GENERAL", "NL: NPO 3" to "NL| GENERAL",
            "ESPN NEWS" to "SPORTS NEWS",
        )
        library.sync()

        // Canada (its two language codes as one country), then English's countries the Provider has, then by count: the Netherlands' 3 over France's 1.
        assertEquals(
            listOf("CA" to 2, "US" to 3, "GB" to 2, "DE" to 4, "NL" to 3, "FR" to 1),
            library.liveCountries("CA", listOf("en")).map { it.code to it.channels },
        )
        // A French-speaking Viewer in France: France, then French's countries (Belgium and Switzerland the Provider lacks), then by count.
        assertEquals(listOf("FR", "CA", "DE", "NL", "US", "GB"), library.liveCountries("FR", listOf("fr")).map { it.code })
        // A Region the Provider lacks is simply absent.
        assertEquals(listOf("DE", "NL", "US", "CA", "GB", "FR"), library.liveCountries("JP", emptyList()).map { it.code })
    }

    @Test fun `a code that isn't a country gets its own card after the countries when the Prefix table names it, and is absent otherwise`() = runBlocking {
        provider.serveChannels(
            "FR: TF1" to "FR| GENERAL",
            "AR: MBC 1" to "AR| GENERAL", "AR: MBC 2" to "AR| GENERAL",
            "LA: TELEMUNDO" to "LA| GENERAL",
            "EX YU: RTS 1" to "EX YU| GENERAL", "EX YU: HRT 1" to "EX YU| GENERAL", "EX YU: RTCG" to "EX YU| GENERAL",
            "XX: NOWHERE 1" to "XX| GENERAL",
        )
        library.sync()

        val countries = library.liveCountries("FR", listOf("fr"))
        // France, then the aliased codes by Channel count: AR is Arabic here, not Argentina; XX is neither and is out.
        assertEquals(listOf("FR" to null, "EX YU" to "Ex-Yugoslavia", "AR" to "Arabic", "LA" to "Latin America"), countries.map { it.code to it.alias })
        assertEquals(listOf(1, 3, 2, 1), countries.map { it.channels })
        assertEquals(listOf("GENERAL · 3"), categories("EX YU"))
        assertEquals(emptyList<String>(), categories("XX"))
    }

    @Test fun `a country's Categories of two languages stay apart, named with the language`() = runBlocking {
        provider.serveChannels(
            "CA EN: TSN 1" to "CA EN| SPORTS",
            "CA EN: SPORTSNET" to "CA EN| SPORTS",
            "CA FR: RDS" to "CA FR| SPORTS",
            "CA FR: TVA" to "CA FR| GÉNÉRAL",
            "CA: CBC" to "CA| GENERAL",
        )
        library.sync()

        assertEquals(listOf("CA" to 5), library.liveCountries("CA", emptyList()).map { it.code to it.channels })
        assertEquals(listOf("SPORTS · EN · 2", "SPORTS · FR · 1", "GÉNÉRAL · FR · 1", "GENERAL · 1"), categories("CA"))
        assertEquals(listOf("CA FR: RDS"), channels("CA", "SPORTS · FR"))
    }

    @Test fun `a Channel in two Categories of a country counts once, and Clutter and Event Categories count for nothing`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "GOLD: TNT SPORT 1 SD" to "UK| SPORTS ᴳᴼᴸᴰ",
            "UK: BBC ONE" to "UK| GENERAL",
            "UK: BBC RADIO 2" to "UK| RADIO",
            "- NO EVENT STREAMING - | 8K EXCLUSIVE | UK: PPV 1" to "UK| PPV",
            "- NO EVENT STREAMING - | 8K EXCLUSIVE | UK: PPV 2" to "UK| PPV",
            "UK: PPV 3" to "UK| PPV",
        )
        library.sync()

        assertEquals(listOf("GB" to 2), library.liveCountries("CA", listOf("en")).map { it.code to it.channels })
    }

    @Test fun `a country's Categories drop the country code and the tags, merge when identical, and carry their Channel counts`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "VIP: TNT SPORT 2 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "UK: BBC ONE" to "UK| GENERAL",
            "UK: BBC TWO" to "UK| GENERAL",
            "UK: BBC NEWS" to "UK| NEWS ᴴᴰ",
            "GOLD: TNT SPORT 1 SD" to "UK| SPORTS ᴳᴼᴸᴰ",
            "GOLD: TNT SPORT 3 SD" to "UK| SPORTS ᴳᴼᴸᴰ",
            "US: CNN" to "US| NEWS",
        )
        library.sync()

        // SPORTS merges the VIP and GOLD Categories, in the first one's place; TNT SPORT 1 is one Channel across them.
        assertEquals(listOf("SPORTS · 3", "GENERAL · 2", "NEWS · 1"), categories("GB"))
        assertEquals(listOf("NEWS · 1"), categories("US"))
        assertEquals(emptyList<String>(), categories("FR"))
    }

    @Test fun `Event Categories and Clutter Categories are absent from a country's Categories`() = runBlocking {
        val onNow = "Arena: Jets vs Oilers (${startedAgo(Duration.ofHours(1))})"
        provider.serveChannels(
            "US: ESPN" to "US| SPORTS",
            // Clutter by its name.
            "US: KISS FM" to "US| RADIO",
            // An Event Category: empty slots and a dated Event against one plain slot.
            "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ARENA PPV 25" to "US| ARENA PPV",
            "US (Arena 526) |  (2098-12-31 08:00:34)" to "US| ARENA PPV",
            onNow to "US| ARENA PPV",
            "US: ARENA PPV 26" to "US| ARENA PPV",
        )
        library.sync()

        assertEquals(listOf("SPORTS · 1"), categories("US"))
    }

    @Test fun `a Category's Channels are whole, in the Provider's order, and a Channel with variants in two Categories is in both`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "UK: SKY SPORTS MAIN EVENT" to "UK| SPORTS ⱽᴵᴾ",
            "UK: BBC ONE" to "UK| GENERAL",
            "UK: BBC ONE HD" to "UK| GENERAL",
            "GOLD: TNT SPORT 1 SD" to "UK| SPORTS ᴳᴼᴸᴰ",
            "UK: BBC ONE SD" to "UK| ENTERTAINMENT",
        )
        library.sync()

        assertEquals(listOf("TNT SPORT 1", "UK: SKY SPORTS MAIN EVENT"), channels("GB", "SPORTS"))
        assertEquals(listOf("UK: BBC ONE"), channels("GB", "GENERAL"))
        assertEquals(listOf("UK: BBC ONE"), channels("GB", "ENTERTAINMENT"))
        // Whole: every variant, whichever Category it was filed under, so watching tries them all.
        val tnt = library.categoryChannels(library.browsedCategories("GB").first { it.name == "SPORTS" }).first()
        assertEquals(listOf("VIP: TNT SPORT 1 ᴴᴰ", "GOLD: TNT SPORT 1 SD"), tnt.variants.map { it.name })
        val bbc = library.categoryChannels(library.browsedCategories("GB").first { it.name == "ENTERTAINMENT" }).single()
        assertEquals(listOf("UK: BBC ONE", "UK: BBC ONE HD", "UK: BBC ONE SD"), bbc.variants.map { it.name })
    }

    @Test fun `a Category's Channels know whether they are Favourites, and a finished Event channel is left out`() = runBlocking {
        val over = "Arena: Jets vs Oilers (${startedAgo(Duration.ofHours(30))})"
        provider.serveChannels(
            "UK: BBC ONE" to "UK| GENERAL",
            "UK: BBC TWO" to "UK| GENERAL",
            over to "UK| GENERAL",
        )
        library.sync()
        library.setFavourite("UK: BBC TWO", true)

        val channels = library.categoryChannels(library.browsedCategories("GB").single())
        assertEquals(listOf("UK: BBC ONE" to false, "UK: BBC TWO" to true), channels.map { it.key to it.favourite })
        assertEquals("GENERAL · 2", categories("GB").single())
    }

    @Test fun `the Live tab closes with the Channels row, after the Favourites`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        library.setFavourite("CA: TSN 1", true)

        val rows = library.liveRows(region = "CA", contentLanguages = listOf("en"))
        assertEquals(listOf(HomeRow.Favourites::class, HomeRow.Channels::class), rows.map { it::class })
        assertEquals(listOf("CA", "US"), (rows.last() as HomeRow.Channels).countries.map { it.code })
    }
}
