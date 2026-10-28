package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Reading when an Event channel's Game starts from the ways the surveyed Provider writes it (#19's survey of 2026-10-28). */
class EventStartTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun start(name: String) = eventStart(name, now)

    @Test fun `dates with a year`() {
        assertEquals(Instant.parse("2026-10-29T18:00:00Z"), start("US (ESPN+ 158) | ACCNX: #9 Notre Dame vs. California (2026-10-29 18:00:30)"))
        assertEquals(Instant.parse("2026-10-28T15:00:00Z"), start("Deutschland U17 - Venezuela U17 | FIFA U-17 | 2026-10-28 | 15:00 (GMT) | 8K EXCLUSIVE | AT: DAZN PPV 52"))
        assertEquals(Instant.parse("2026-10-28T20:30:00Z"), start("Bayer 04 vs. 1.FCN | all | 28-10-2026 | 20:30 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 18"))
    }

    @Test fun `dates without a year take the nearest one`() {
        assertEquals(Instant.parse("2026-10-27T19:00:00Z"), start("Moravian vs Elizabethtown @ Oct 27 7:00 PM :Flo College  49"))
        assertEquals(Instant.parse("2026-10-21T12:30:00Z"), start("ATP TENNIS: BRYSSEL | Wed 21 Oct 12:30 EEST (FI) | 8K EXCLUSIVE | FI: PLAY+ PPV 2"))
        assertEquals(Instant.parse("2026-10-22T19:00:00Z"), start("Flo (FLSP) 90: 2025 Keene State vs VTSU-Castleton - Womens - 22/10 19:00"))
        assertEquals(Instant.parse("2027-01-02T20:35:00Z"), start("RFEF EVENTO 21 | UD Ibiza VS CE Europa J10 02/01 20:35"))
        assertEquals(Instant.parse("2027-02-09T15:00:00Z"), start("FA Player 06 : Portsmouth vs Sunderland // UK Sun 9 Feb 3:00pm // ET Sun 9 Feb 10:00am"))
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
