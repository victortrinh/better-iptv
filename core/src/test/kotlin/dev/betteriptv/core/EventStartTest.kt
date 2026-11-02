package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Reading when an Event channel's Game starts from the ways the surveyed Provider writes it (#19's
 * survey of 2026-10-28), in the Provider's zone (Toronto's, the Prefix table's event time zone)
 * unless the name states one.
 */
class EventStartTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val toronto = ZoneId.of("America/Toronto")

    private fun start(name: String) = eventStart(name, now, toronto)

    @Test fun `dates with a year, in the Provider's zone`() {
        // 6 PM in Toronto on a summer-time day: 22:00 UTC.
        assertEquals(Instant.parse("2026-10-29T22:00:00Z"), start("US (ESPN+ 158) | ACCNX: #9 Notre Dame vs. California (2026-10-29 18:00:30)"))
        assertEquals(Instant.parse("2026-10-28T23:00:00Z"), start("Lakers vs Celtics @ Oct 28, 2026 7:00 PM"))
        // A country tag at the start is the Channel's, not the time's zone.
        assertEquals(Instant.parse("2026-10-28T23:00:00Z"), start("UK: Leafs vs Habs (2026-10-28 19:00:00)"))
    }

    @Test fun `a zone the name states wins over the Provider's`() {
        assertEquals(Instant.parse("2026-10-28T15:00:00Z"), start("Deutschland U17 - Venezuela U17 | FIFA U-17 | 2026-10-28 | 15:00 (GMT) | 8K EXCLUSIVE | AT: DAZN PPV 52"))
        assertEquals(Instant.parse("2026-10-28T20:30:00Z"), start("Bayer 04 vs. 1.FCN | all | 28-10-2026 | 20:30 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 18"))
        // Helsinki's summer time, three hours ahead of UTC.
        assertEquals(Instant.parse("2026-10-21T09:30:00Z"), start("ATP TENNIS: BRYSSEL | Wed 21 Oct 12:30 EEST (FI) | 8K EXCLUSIVE | FI: PLAY+ PPV 2"))
        // The zone before the date, a weekday between; February, so London is on GMT.
        assertEquals(Instant.parse("2027-02-09T15:00:00Z"), start("FA Player 06 : Portsmouth vs Sunderland // UK Sun 9 Feb 3:00pm // ET Sun 9 Feb 10:00am"))
    }

    @Test fun `dates without a year take the nearest one`() {
        assertEquals(Instant.parse("2026-10-27T23:00:00Z"), start("Moravian vs Elizabethtown @ Oct 27 7:00 PM :Flo College  49"))
        assertEquals(Instant.parse("2026-10-22T23:00:00Z"), start("Flo (FLSP) 90: 2025 Keene State vs VTSU-Castleton - Womens - 22/10 19:00"))
        // January in Toronto: five hours behind UTC.
        assertEquals(Instant.parse("2027-01-03T01:35:00Z"), start("RFEF EVENTO 21 | UD Ibiza VS CE Europa J10 02/01 20:35"))
    }

    @Test fun `names without a date have none`() {
        assertEquals(null, start("US: 24/7 MARVEL 4"))
        assertEquals(null, start("Boxing 1 | SMITH vs PUELLO     UK 5:00pm"))
        assertEquals(null, start("UK: TNT SPORTS EVENT 12"))
    }

    @Test fun `an Event marked on now or next has none, whatever its date`() {
        assertEquals(null, start("LIVE | KOSTENLOS 24 7 CLIPMYHORSE TV | Tue 07 Apr 03:40 CEST (NL) | 8K EXCLUSIVE | NL: CLIPMYHORSE PPV 2"))
        assertEquals(null, start("Next | Leafs vs Habs | 2026-01-02 | 01:00 (GMT)"))
    }

    @Test fun `an Event is finished six hours after it starts`() {
        assertFalse(finished(Instant.parse("2026-10-28T06:01:00Z"), now))
        assertTrue(finished(Instant.parse("2026-10-28T05:59:00Z"), now))
        assertFalse(finished(null, now))
    }
}
