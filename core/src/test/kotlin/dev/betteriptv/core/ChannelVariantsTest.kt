package dev.betteriptv.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Channel variants through the Library (#251): the Provider's Listings of one Channel merge into it,
 * play best first, and are favourited as one.
 */
class ChannelVariantsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val catalog by lazy { folder.root.resolve("catalog.db").path }
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(catalog, InMemoryCredentials())
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private val english = listOf("en")

    /** A stick that decodes HEVC, on a 4K TV. */
    private val hevcStick = DeviceProfile(maxHeight = 2160, hdr = emptySet(), videoDecoders = setOf("video/avc", "video/hevc"))

    /** A stick without an HEVC decoder, on a 1080p TV. */
    private val oldStick = DeviceProfile(maxHeight = 1080, hdr = emptySet(), videoDecoders = setOf("video/avc"))

    // Against the catalog as it stands: after a Sync, search keeps the last index until a background rebuild
    // lands, and a renumbered stream id could find another Channel in it meanwhile.
    private suspend fun channels(query: String): List<Channel> {
        library.prepareSearch()
        return library.search(query, english, "en").last().channels
    }

    private suspend fun channel(query: String) = channels(query).single()

    private suspend fun favouritesRow() = library.homeRows(english, "en").filterIsInstance<HomeRow.Favourites>().singleOrNull()?.channels

    private fun Channel.variantNames() = variants.map { it.name }

    // Merging

    @Test fun `variants across Tier tags and Categories merge into one Channel, in the Provider's order`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ" to "UK| SPORTS ⱽᴵᴾ",
            "VIP: TNT SPORT 2 ᴴᴰ" to "UK| SPORTS ⱽᴵᴾ",
            "VIP: TNT SPORT 1 SD" to "UK| SPORTS ⱽᴵᴾ",
            "GOLD: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS ᴳᴼᴸᴰ",
        )
        library.sync()

        val found = channels("tnt sport")
        assertEquals(listOf("TNT SPORT 1", "TNT SPORT 2"), found.map { it.name })
        val one = found.first()
        assertEquals(listOf("VIP: TNT SPORT 1 ᴴᴰ", "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ", "VIP: TNT SPORT 1 SD", "GOLD: TNT SPORT 1 ᴴᴰ"), one.variantNames())
        // Both Categories, so the browser can list it under either package.
        assertEquals(listOf("UK| SPORTS ⱽᴵᴾ", "UK| SPORTS ᴳᴼᴸᴰ"), one.categoryIds.map { id -> library.liveCategories().single { it.id == id }.name })
        assertEquals(listOf(1L, 2L, 4L, 5L), one.variants.map { it.id })
    }

    @Test fun `the country tag keeps namesakes apart`() = runBlocking {
        provider.serveChannels("UK: CNN HD" to "UK| NEWS", "US: CNN HD" to "US| NEWS", "US: CNN SD" to "US| NEWS", "CA EN: CNN" to "CA| NEWS")
        library.sync()

        val found = channels("cnn")
        assertEquals(listOf("UK: CNN", "US: CNN", "CA EN: CNN"), found.map { it.key })
        assertEquals(listOf("CNN", "CNN", "CNN"), found.map { it.name })
        assertEquals(listOf("UK", "US", "CA EN"), found.map { it.country })
        assertEquals(listOf("US: CNN HD", "US: CNN SD"), found[1].variantNames())
    }

    @Test fun `identical names are variants of one Channel`() = runBlocking {
        provider.serveChannels("UK: BBC ONE NI HD ◉" to "UK| GENERAL", "UK: BBC ONE NI HD ◉" to "UK| GENERAL")
        library.sync()

        val one = channel("bbc one")
        assertEquals("UK: BBC ONE NI", one.key)
        assertEquals("BBC ONE NI", one.name)
        assertEquals(listOf(1L, 2L), one.variants.map { it.id })
    }

    @Test fun `the survey's spellings merge, superscripts, 4K and 8K packages, (SAT), (720P), HEVC and an emoji`() = runBlocking {
        provider.serveChannels(
            "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ" to "UK| 4K",
            "VIP: SKY SPORTS F1 ᴴᴰ" to "UK| SPORTS",
            "DE: SKY SPORT BUNDESLIGA 1 HD (SAT)" to "DE| SPORT",
            "DE: SKY SPORT BUNDESLIGA 1 HD (720P)" to "DE| SPORT",
            "DE: SKY SPORT BUNDESLIGA 1 HEVC" to "DE| SPORT",
            "8K: beIN SP⚽RTS 1 SD" to "8K| SPORTS",
            "GOLD: beIN SP⚽RTS 1 ᴴᴰ" to "GOLD| SPORTS",
            "US: NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ" to "US| NBC",
            "TR VIP: BEIN SPORTS 1 HD+" to "TR| SPORTS",
            "TR: BEIN SPORTS 1" to "TR| SPORTS",
        )
        library.sync()

        assertEquals(listOf("4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ", "VIP: SKY SPORTS F1 ᴴᴰ"), channel("sky sports f1").variantNames())
        val bundesliga = channel("bundesliga")
        assertEquals("DE: SKY SPORT BUNDESLIGA 1", bundesliga.key)
        assertEquals(3, bundesliga.variants.size)
        val bein = channels("bein").single { it.country == null }
        assertEquals("beIN SP⚽RTS 1", bein.name)
        assertEquals(listOf("8K: beIN SP⚽RTS 1 SD", "GOLD: beIN SP⚽RTS 1 ᴴᴰ"), bein.variantNames())
        assertEquals("NBC", channel("nbc").name)
        assertEquals(listOf("TR VIP: BEIN SPORTS 1 HD+", "TR: BEIN SPORTS 1"), channels("bein").single { it.country == "TR" }.variantNames())
    }

    @Test fun `a Channel is named without its tags, captioned with its country, with the first variant's logo`() = runBlocking {
        provider.serveChannels("VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS", "GOLD: TNT SPORT 1 SD" to "UK| SPORTS", "CA FR: TVA SPORTS HD" to "CA FR| SPORTS")
        library.sync()

        val tnt = channel("tnt")
        assertEquals("TNT SPORT 1", tnt.name)
        assertNull(tnt.country)
        assertEquals("http://logo/1.png", tnt.image)
        val tva = channel("tva")
        assertEquals("TVA SPORTS", tva.name)
        assertEquals("CA FR", tva.country)
        assertEquals("CA FR: TVA SPORTS", tva.key)
    }

    @Test fun `a variant carries the Likely quality its name says, and whether it is HEVC`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS",
            "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ" to "UK| SPORTS",
            "VIP: TNT SPORT 1 SD" to "UK| SPORTS",
            "VIP: TNT SPORT 1 ᴿᴬᵂ" to "UK| SPORTS",
            "4K: TNT SPORT 1 ᵁᴴᴰ" to "UK| 4K",
            "VIP: TNT SPORT 1 FHD" to "UK| SPORTS",
            "VIP: TNT SPORT 1" to "UK| SPORTS",
        )
        library.sync()

        val variants = channel("tnt").variants
        assertEquals(listOf(Quality.HD, Quality.HD, Quality.SD, Quality.HD, Quality.UHD, Quality.HD, null), variants.map { it.quality })
        assertEquals(listOf(false, true, false, false, false, false, false), variants.map { it.hevc })
    }

    @Test fun `quality words are read off the end of the name only, and RAW only as the Provider's superscript tag`() = runBlocking {
        provider.serveChannels(
            "US: WWE RAW" to "US| SPORTS",
            "US: WWE RAW HD" to "US| SPORTS",
            "US: HD NET" to "US| GENERAL",
            "US: HD NET ᴿᴬᵂ" to "US| GENERAL",
            "GOLD: beIN SP⚽RTS 1 RAW" to "GOLD| SPORTS",
        )
        library.sync()

        val wwe = channel("wwe")
        assertEquals("WWE RAW", wwe.name)
        assertEquals(listOf(null, Quality.HD), wwe.variants.map { it.quality })
        val net = channel("net")
        assertEquals("HD NET", net.name)
        assertEquals(listOf(null, Quality.HD), net.variants.map { it.quality })
        // Plain RAW at the end stays, so this Listing is its own Channel.
        assertEquals("beIN SP⚽RTS 1 RAW", channel("bein").name)
    }

    // Ranking and the play plan

    /** A Channel with a variant in each quality, HEVC variants at two of them, and one whose name says nothing. */
    private suspend fun syncEveryQuality() {
        provider.serveChannels(
            "VIP: TNT SPORT 1 SD" to "UK| SPORTS",
            "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ" to "UK| SPORTS",
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS",
            "VIP: TNT SPORT 1" to "UK| SPORTS",
            "4K: TNT SPORT 1 ᵁᴴᴰ ʰᵉᵛᶜ" to "UK| 4K",
            "4K: TNT SPORT 1 ᵁᴴᴰ" to "UK| 4K",
            "GOLD: TNT SPORT 1 ᴴᴰ" to "UK| GOLD",
        )
        library.sync()
    }

    private suspend fun plan(device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD) = library.channelPlayPlan(channel("tnt"), english, device, qualityCap)

    @Test fun `variants rank by quality under the Quality cap, HEVC first within a quality when the stick decodes it, then in the Provider's order`() = runBlocking {
        syncEveryQuality()

        assertEquals(
            listOf("4K: TNT SPORT 1 ᵁᴴᴰ ʰᵉᵛᶜ", "4K: TNT SPORT 1 ᵁᴴᴰ", "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ", "VIP: TNT SPORT 1 ᴴᴰ", "GOLD: TNT SPORT 1 ᴴᴰ", "VIP: TNT SPORT 1 SD", "VIP: TNT SPORT 1"),
            plan(hevcStick).versions.map { it.name },
        )
        // Under a 1080p cap the 4K variants come last, as a Movie's Versions above the cap do.
        assertEquals(
            listOf("VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ", "VIP: TNT SPORT 1 ᴴᴰ", "GOLD: TNT SPORT 1 ᴴᴰ", "VIP: TNT SPORT 1 SD", "VIP: TNT SPORT 1", "4K: TNT SPORT 1 ᵁᴴᴰ ʰᵉᵛᶜ", "4K: TNT SPORT 1 ᵁᴴᴰ"),
            plan(hevcStick, QualityCap.FullHD).versions.map { it.name },
        )
        // A stick without an HEVC decoder (on a 1080p TV) puts the HEVC variants after the others of their quality.
        assertEquals(
            listOf("VIP: TNT SPORT 1 ᴴᴰ", "GOLD: TNT SPORT 1 ᴴᴰ", "VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ", "VIP: TNT SPORT 1 SD", "VIP: TNT SPORT 1", "4K: TNT SPORT 1 ᵁᴴᴰ", "4K: TNT SPORT 1 ᵁᴴᴰ ʰᵉᵛᶜ"),
            plan(oldStick).versions.map { it.name },
        )
    }

    @Test fun `the play plan plays each variant as MPEG-TS then HLS, in rank order`() = runBlocking {
        provider.serveChannels("VIP: TNT SPORT 1 SD" to "UK| SPORTS", "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS", "4K: TNT SPORT 1 ᵁᴴᴰ" to "UK| 4K")
        library.sync()

        val plan = plan(hevcStick)
        assertTrue(plan.live)
        val chain = (listOf(plan.version) + plan.version.backups).map { it.listingId to it.liveFormat }
        assertEquals(listOf(3L to "ts", 3L to "m3u8", 2L to "ts", 2L to "m3u8", 1L to "ts", 1L to "m3u8"), chain)
        // The list the player's menu key shows: one Version per variant, each going on down the chain.
        assertEquals(listOf(3L, 2L, 1L), plan.versions.map { it.listingId })
        assertEquals(plan.version, plan.versions.first())
        assertEquals(listOf(2L to "m3u8", 1L to "ts", 1L to "m3u8"), plan.versions[1].backups.map { it.listingId to it.liveFormat })
        assertEquals(Known(Quality.UHD, Provenance.Likely), plan.version.attributes.quality)
        assertEquals(Known(Quality.SD, Provenance.Likely), plan.versions.last().attributes.quality)
    }

    @Test fun `watching tries three variants at most, though the list shows them all`() = runBlocking {
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS",
            "VIP: TNT SPORT 1 ᴴᴰ ◉" to "UK| SPORTS",
            "GOLD: TNT SPORT 1 ᴴᴰ" to "UK| GOLD",
            "VIP: TNT SPORT 1 SD" to "UK| SPORTS",
            "GOLD: TNT SPORT 1 SD" to "UK| GOLD",
        )
        library.sync()

        val plan = plan(hevcStick)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.versions.map { it.listingId })
        // Six attempts: each of the first three variants as TS then HLS; a Channel dead at the Provider fails there.
        assertEquals(listOf(1L to "ts", 1L to "m3u8", 2L to "ts", 2L to "m3u8", 3L to "ts", 3L to "m3u8"), (listOf(plan.version) + plan.version.backups).map { it.listingId to it.liveFormat })
        // Picking a variant from the list goes on the same way from it.
        assertEquals(listOf(3L to "m3u8", 4L to "ts", 4L to "m3u8", 5L to "ts", 5L to "m3u8"), plan.versions[2].backups.map { it.listingId to it.liveFormat })
        assertEquals(listOf(5L to "m3u8"), plan.versions[4].backups.map { it.listingId to it.liveFormat })
    }

    private val player by lazy { ScriptedPlayer(provider) }

    private suspend fun PlaybackSession.awaitPlaying() = withTimeout(5_000) { state.first { it is PlaybackState.Playing } as PlaybackState.Playing }

    @Test fun `a refused variant gives way to the next`() = runBlocking {
        provider.serveChannels("VIP: TNT SPORT 1 SD" to "UK| SPORTS", "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS")
        provider.deadStreams += 2
        library.sync()

        val session = library.playback(plan(hevcStick), player.engines)
        val job: Job = GlobalScope.launch { session.play() }
        val playing = session.awaitPlaying()
        assertEquals(1L, playing.version.listingId)
        assertEquals("ts", playing.version.liveFormat)
        // Both formats of the dead variant were tried before it gave way.
        assertEquals(listOf("/2.ts", "/2.m3u8", "/1.ts"), player.locations.map { it.substring(it.lastIndexOf('/')) })
        job.cancelAndJoin()
        assertEquals(0, provider.refusedStreams)
    }

    // Favourites

    @Test fun `a Favourite is the whole Channel, and Home and Search show it once`() = runBlocking {
        provider.serveChannels("VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS", "GOLD: TNT SPORT 1 SD" to "UK| GOLD", "VIP: TNT SPORT 2 ᴴᴰ" to "UK| SPORTS")
        library.sync()
        library.setFavourite(channel("tnt sport 1").key, true)

        assertEquals(listOf("TNT SPORT 1"), favouritesRow()?.map { it.name })
        assertEquals(2, favouritesRow()!!.single().variants.size)
        assertEquals(listOf("TNT SPORT 1", "TNT SPORT 2"), channels("tnt").map { it.name })
        assertTrue(channel("tnt sport 1").favourite)
        assertFalse(channel("tnt sport 2").favourite)
        assertEquals(listOf("TNT SPORT 1"), library.liveRows().filterIsInstance<HomeRow.Favourites>().single().channels.map { it.name })

        library.setFavourite(channel("tnt sport 1").key, false)
        assertNull(favouritesRow())
    }

    @Test fun `a Favourite survives a Sync that drops or renumbers a variant`() = runBlocking {
        provider.serveChannels("VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS", "GOLD: TNT SPORT 1 SD" to "UK| GOLD")
        library.sync()
        library.setFavourite(channel("tnt").key, true)

        // The HD variant goes and the SD one gets a new stream id, under a new package.
        provider.serveChannels("UK: NEWS" to "UK| GENERAL", "8K: TNT SPORT 1 SD" to "8K| SPORTS")
        library.sync()

        val favourite = favouritesRow()!!.single()
        assertEquals("TNT SPORT 1", favourite.name)
        assertEquals(listOf(2L), favourite.variants.map { it.id })
        assertTrue(channel("tnt").favourite)
    }

    @Test fun `Favourites kept by stream id are carried over to their Channels once, by the next Sync`() = runBlocking {
        library.close()
        // A catalog an older app left: Favourites by stream id, the Channel the id was a variant of still to be Synced.
        val driver = BundledSQLiteDriver()
        driver.open(catalog).apply {
            execSQL("DROP TABLE IF EXISTS favourite")
            execSQL("CREATE TABLE favourite (id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)")
            execSQL("INSERT INTO favourite VALUES (3, 1000), (2, 2000), (1, 3000), (9, 4000)")
            close()
        }
        library = Library.open(catalog, InMemoryCredentials())
        library.logIn(provider.login)
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS",
            "GOLD: TNT SPORT 1 SD" to "UK| GOLD",
            "UK: BBC ONE HD" to "UK| GENERAL",
            "UK: BBC TWO HD" to "UK| GENERAL",
        )
        library.sync()

        // In the order they were added; the two ids of TNT SPORT 1 make one Favourite, and id 9 is nobody's.
        assertEquals(listOf("BBC ONE", "TNT SPORT 1"), favouritesRow()?.map { it.name })
        assertTrue(channel("tnt").favourite)

        // Once carried over, the old ids don't come back as Favourites of whatever has them next.
        library.setFavourite(channel("bbc one").key, false)
        provider.serveChannels("UK: BBC ONE HD" to "UK| GENERAL", "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS")
        library.sync()
        assertEquals(listOf("TNT SPORT 1"), favouritesRow()?.map { it.name })
    }

    @Test fun `Favourites by stream id survive the older app running again before the Sync that carries them over`() = runBlocking {
        library.close()
        val driver = BundledSQLiteDriver()
        driver.open(catalog).apply {
            execSQL("DROP TABLE IF EXISTS favourite")
            execSQL("CREATE TABLE favourite (id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)")
            execSQL("INSERT INTO favourite VALUES (3, 1000), (1, 2000)")
            close()
        }
        // The upgraded app opens the catalog (and sets them aside), but the Viewer goes back to the older app before a Sync.
        Library.open(catalog, InMemoryCredentials()).close()
        driver.open(catalog).apply {
            execSQL("DROP TABLE IF EXISTS favourite")
            execSQL("CREATE TABLE favourite (id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)")
            execSQL("INSERT INTO favourite VALUES (4, 3000)")
            close()
        }
        library = Library.open(catalog, InMemoryCredentials())
        library.logIn(provider.login)
        provider.serveChannels(
            "VIP: TNT SPORT 1 ᴴᴰ" to "UK| SPORTS",
            "GOLD: TNT SPORT 1 SD" to "UK| GOLD",
            "UK: BBC ONE HD" to "UK| GENERAL",
            "UK: BBC TWO HD" to "UK| GENERAL",
        )
        library.sync()

        // Both rounds' Favourites, in the order they were added.
        assertEquals(listOf("BBC ONE", "TNT SPORT 1", "BBC TWO"), favouritesRow()?.map { it.name })
    }
}
