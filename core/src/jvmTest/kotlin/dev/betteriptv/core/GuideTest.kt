package dev.betteriptv.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Now and next through the Library (#261): the Provider's per-Channel guide call is made for the
 * Channel rows on screen and the Favourites that have a guide id, its programmes are kept until the
 * last of them ends, and the first refusal stops the calls for the day, as Background reading stops.
 */
class GuideTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library
    private val clock = TestClock(Instant.parse("2026-11-02T20:30:00Z"))
    private val now get() = clock.now

    @Before fun logIn() = runBlocking {
        provider.guideNow = { clock.now }
        library = open()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private fun open() = Library.open(folder.root.resolve("catalog.db").path, credentials).also { it.guideClock = clock }

    private fun programme(title: String, from: Duration, to: Duration) = FakeProvider.GuideProgramme(title, now + from, now + to)

    /** Syncs a Canadian lineup: CBC (stream 1) and TSN 1 (streams 2 and 3) known to the guide, CTV (stream 4) not. */
    private suspend fun syncLineup() {
        provider.serveChannels(
            FakeProvider.LiveListing("CA: CBC", "CA| GENERAL", guideId = "cbc.ca"),
            FakeProvider.LiveListing("VIP: CA: TSN 1 ᴴᴰ", "CA| SPORTS", guideId = "tsn1.ca"),
            FakeProvider.LiveListing("GOLD: CA: TSN 1 SD", "CA| SPORTS", guideId = "tsn1.ca"),
            FakeProvider.LiveListing("CA: CTV", "CA| GENERAL", guideId = ""),
        )
        library.sync()
    }

    private suspend fun channel(key: String): Channel =
        library.browsedCategories("CA").flatMap { library.categoryChannels(it) }.first { it.key == key }

    private suspend fun awaitGuideRequests(count: Int) = withTimeout(10_000) { while (provider.guideRequests.size < count) delay(10) }

    private suspend fun awaitGuide(channel: Channel): NowNext = withTimeout(10_000) {
        while (library.guide(channel) == null) delay(10)
        library.guide(channel)!!
    }

    /** Lets the guide thread make any call it was going to make. */
    private suspend fun settle() = delay(300)

    @Test fun `the guide is asked for the Channels on screen that have a guide id, and a Channel's titles are decoded`() = runBlocking {
        syncLineup()
        provider.serveGuide(
            1,
            programme("The National", Duration.ofMinutes(-30), Duration.ofMinutes(30)),
            programme("Dragons' Den", Duration.ofMinutes(30), Duration.ofMinutes(90)),
        )
        val cbc = channel("CA: CBC")
        val ctv = channel("CA: CTV")

        library.guideOnScreen(listOf(ctv, cbc))

        val onCbc = awaitGuide(cbc)
        assertEquals("The National", onCbc.now?.title)
        assertEquals("Dragons' Den", onCbc.next?.title)
        assertEquals(now + Duration.ofMinutes(30), onCbc.next?.start)
        settle()
        // CTV has no guide id: never asked about, nothing to show.
        assertEquals(listOf(1L), provider.guideRequests)
        assertNull(library.guide(ctv))
    }

    @Test fun `the programme on now shows in its last hour, and the ones already over don't`() = runBlocking {
        syncLineup()
        // As the Provider answered for A & E at 14:17 on 2026-11-03 (#306): half-hours, the one on now
        // ending in 13 minutes, which the short guide leaves out.
        provider.serveGuide(
            1,
            programme("Storage Wars", Duration.ofMinutes(-47), Duration.ofMinutes(-17)),
            programme("Hoarders", Duration.ofMinutes(-17), Duration.ofMinutes(13)),
            programme("Hoarders", Duration.ofMinutes(13), Duration.ofMinutes(43)),
            programme("Intervention", Duration.ofMinutes(43), Duration.ofMinutes(103)),
        )
        val cbc = channel("CA: CBC")

        library.guideOnScreen(listOf(cbc))

        val onCbc = awaitGuide(cbc)
        assertEquals("Hoarders", onCbc.now?.title)
        assertEquals(now + Duration.ofMinutes(13), onCbc.next?.start)
        settle()
        assertEquals(listOf(1L), provider.guideRequests)
    }

    @Test fun `a merged Channel is asked about once, by the variant the guide knows, and a Channel off screen is not`() = runBlocking {
        syncLineup()
        provider.serveGuide(2, programme("Hockey Night", Duration.ofMinutes(-10), Duration.ofHours(2)))
        val tsn = channel("CA: TSN 1")

        library.guideOnScreen(listOf(tsn))

        assertEquals("Hockey Night", awaitGuide(tsn).now?.title)
        settle()
        assertEquals(listOf(2L), provider.guideRequests)
        assertNull(library.guide(channel("CA: CBC")))
    }

    @Test fun `the Favourites' guide is asked for when Home's or Live's rows are read`() = runBlocking {
        syncLineup()
        provider.serveGuide(1, programme("The National", Duration.ofMinutes(-30), Duration.ofMinutes(30)))
        library.setFavourite("CA: CBC", true)

        val rows = library.liveRows(region = "CA", contentLanguages = listOf("en"))
        val favourite = rows.filterIsInstance<HomeRow.Favourites>().single().channels.single()

        assertEquals("The National", awaitGuide(favourite).now?.title)
        assertEquals(listOf(1L), provider.guideRequests)
    }

    @Test fun `a Channel's guide is kept until its last programme ends, then asked for again`() = runBlocking {
        syncLineup()
        provider.serveGuide(
            1,
            programme("The National", Duration.ofMinutes(-30), Duration.ofMinutes(30)),
            programme("Dragons' Den", Duration.ofMinutes(30), Duration.ofMinutes(90)),
        )
        val cbc = channel("CA: CBC")
        library.guideOnScreen(listOf(cbc))
        awaitGuideRequests(1)

        // Back on screen within the hour: the cache answers, and the next programme has become the one on.
        clock.now += Duration.ofMinutes(45)
        library.guideOnScreen(listOf(cbc))
        settle()
        assertEquals(1, provider.guideRequests.size)
        assertEquals("Dragons' Den" to null, library.guide(cbc)!!.let { it.now?.title to it.next })

        // Once the last programme is over, nothing is shown and the Channel is asked about again.
        clock.now += Duration.ofMinutes(50)
        assertNull(library.guide(cbc))
        provider.serveGuide(1, programme("Marketplace", Duration.ofMinutes(-5), Duration.ofMinutes(25)))
        library.guideOnScreen(listOf(cbc))
        assertEquals("Marketplace", awaitGuide(cbc).now?.title)
        assertEquals(listOf(1L, 1L), provider.guideRequests)
    }

    @Test fun `a Channel the guide lists nothing for shows nothing, and isn't asked about again for a while`() = runBlocking {
        syncLineup()
        val cbc = channel("CA: CBC")

        library.guideOnScreen(listOf(cbc))
        awaitGuideRequests(1)
        settle()
        assertNull(library.guide(cbc))

        library.guideOnScreen(listOf(cbc))
        settle()
        assertEquals(listOf(1L), provider.guideRequests)
    }

    @Test fun `the first refusal stops the guide for the day, and the next day it is asked again`() = runBlocking {
        syncLineup()
        provider.serveGuide(1, programme("The National", Duration.ofMinutes(-30), Duration.ofMinutes(30)))
        provider.serveGuide(2, programme("Hockey Night", Duration.ofMinutes(-10), Duration.ofHours(2)))
        provider.refuseGuideWith = 429
        val cbc = channel("CA: CBC")
        val tsn = channel("CA: TSN 1")

        library.guideOnScreen(listOf(cbc, tsn))
        awaitGuideRequests(1)
        settle()

        // Refused once: TSN 1 is not asked about, and nothing shows for either.
        assertEquals(listOf(1L), provider.guideRequests)
        assertNull(library.guide(cbc))
        assertNull(library.guide(tsn))
        // Even when the Provider would answer now, and even from a fresh start the same day.
        provider.refuseGuideWith = null
        library.guideOnScreen(listOf(tsn))
        settle()
        library.close()
        library = open()
        library.guideOnScreen(listOf(cbc, tsn))
        settle()
        assertEquals(listOf(1L), provider.guideRequests)

        // The next day, the guide is asked for again.
        clock.now += Duration.ofDays(1)
        provider.serveGuide(1, programme("The National", Duration.ofMinutes(-30), Duration.ofMinutes(30)))
        provider.serveGuide(2, programme("Hockey Night", Duration.ofMinutes(-10), Duration.ofHours(2)))
        library.guideOnScreen(listOf(cbc, tsn))
        assertEquals("The National", awaitGuide(cbc).now?.title)
        assertEquals("Hockey Night", awaitGuide(tsn).now?.title)
        assertEquals(listOf(1L, 1L, 2L), provider.guideRequests)
    }

    /** A clock the test moves by hand, in UTC. */
    private class TestClock(@Volatile var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
