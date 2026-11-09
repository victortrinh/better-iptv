package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop and the colour key of a Title treatment (ADR 0016), as scripts/artwork/cutout.swift makes
 * them: the block of the poster's tallest text, and its lettering keyed out of its own background.
 */
class TitleTreatmentCutTest {
    @Test fun `the crop is the tallest line's block, with the small line above it, padded 3% sideways and 1,2% up and down`() {
        val lines = listOf(
            TextLine("THE", 0.40, 0.70, 0.20, 0.03),
            TextLine("HOUSEMAID", 0.10, 0.74, 0.80, 0.08),
            // The credits: wide, but a fifth as tall and far below.
            TextLine("A FILM BY PAUL FEIG", 0.20, 0.95, 0.60, 0.015),
            // A rating, too narrow to count.
            TextLine("R", 0.90, 0.02, 0.03, 0.2),
        )
        val crop = treatmentCrop(lines)!!
        assertEquals("THE | HOUSEMAID", crop.text)
        assertEquals(0.07, crop.x0, 1e-9)
        assertEquals(0.688, crop.y0, 1e-9)
        assertEquals(0.93, crop.x1, 1e-9)
        assertEquals(0.832, crop.y1, 1e-9)
    }

    @Test fun `the block that reads as the name beats a taller one that doesn't`() {
        val lines = listOf(
            // The studio's logo, set bigger than the title.
            TextLine("ILLUMINATION", 0.25, 0.05, 0.5, 0.10),
            TextLine("DESPICABLE ME", 0.10, 0.70, 0.8, 0.06),
        )
        assertEquals("DESPICABLE ME", treatmentCrop(lines, "Despicable Me")!!.text)
        // With no name to go by, the tallest.
        assertEquals("ILLUMINATION", treatmentCrop(lines)!!.text)
    }

    @Test fun `a line too short to be the title doesn't beat the tallest block, even reading as the name`() {
        val lines = listOf(
            TextLine("VEELLRS", 0.1, 0.30, 0.8, 0.12),
            // The credits name the Title, but in letters 1.5% of the poster tall.
            TextLine("AVENGERS ENDGAME", 0.2, 0.95, 0.6, 0.015),
        )
        assertEquals("VEELLRS", treatmentCrop(lines, "Avengers: Endgame")!!.text)
    }

    @Test fun `lines as tall within reach of the tallest are one block`() {
        val lines = listOf(
            TextLine("DEATH", 0.2, 0.10, 0.5, 0.10),
            TextLine("UNICORN", 0.1, 0.22, 0.8, 0.09),
            // As tall, but too far down: another block.
            TextLine("IN CINEMAS", 0.1, 0.60, 0.8, 0.09),
        )
        assertEquals("DEATH | UNICORN", treatmentCrop(lines)!!.text)
    }

    @Test fun `the crop is kept inside the poster`() {
        val crop = treatmentCrop(listOf(TextLine("YOU", 0.0, 0.0, 1.0, 0.2)))!!
        assertEquals(listOf(0.0, 0.0, 1.0), listOf(crop.x0, crop.y0, crop.x1))
        assertEquals(0.212, crop.y1, 1e-9)
        // Widened to whole pixels: 0.212 of 700 is 148.4.
        assertEquals(listOf(0, 0, 500, 149), crop.pixels(500, 700).toList())
    }

    @Test fun `a poster with no line wide enough has no crop`() {
        assertNull(treatmentCrop(listOf(TextLine("4K", 0.9, 0.02, 0.05, 0.03))))
        assertNull(treatmentCrop(emptyList()))
    }

    @Test fun `the key keeps the lettering in its own colour and drops the background`() {
        // A dark blue poster, with a bar of orange lettering in its middle, its edge two pixels of
        // anti-aliasing between the two colours, as a poster's lettering has.
        val ground = 0xFF101830.toInt()
        val letters = 0xFFF08020.toInt()
        val poster = Pixels(100, 40, IntArray(100 * 40) { i ->
            val x = i % 100
            val y = i / 100
            when {
                x in 20 until 80 && y in 15 until 25 -> letters
                x in 18 until 82 && y in 13 until 27 -> mix(ground, letters, ((x + y) % 15 + 1) / 16.0)
                else -> ground
            }
        })
        val keyed = colourKey(poster)
        val out = keyed.cutout
        assertEquals(0, out.argb[0] ushr 24)
        assertEquals(0, out.argb[5 * 100 + 50] ushr 24)
        assertEquals(0xFF, out.argb[20 * 100 + 50] ushr 24)
        assertEquals(letters and 0xFFFFFF, out.argb[20 * 100 + 50] and 0xFFFFFF)
        // The bar is 15% of the crop; its edge adds part of its 7%.
        assertTrue("coverage ${keyed.coverage}", keyed.coverage in 0.15..0.22)
        // Orange: high in chroma (0xF0 - 0x20 is 0.75), the edge's blend a little less.
        assertTrue("chroma ${keyed.chroma}", keyed.chroma in 0.65..0.76)
        assertEquals(TreatmentVerdict.Accepted, treatmentVerdict("Soul", "SOUL", keyed.luma, keyed.chroma, keyed.coverage))
    }

    @Test fun `dark lettering on a dark poster is refused`() {
        val poster = Pixels(100, 40, IntArray(100 * 40) { i ->
            val x = i % 100
            val y = i / 100
            when {
                x in 20 until 80 && y in 15 until 25 -> 0xFF303030.toInt()
                x in 18 until 82 && y in 13 until 27 -> mix(0xFF000000.toInt(), 0xFF303030.toInt(), ((x + y) % 15 + 1) / 16.0)
                else -> 0xFF000000.toInt()
            }
        })
        val keyed = colourKey(poster)
        assertEquals(TreatmentVerdict.DarkTitle, treatmentVerdict("Pranked by Melvin", "PRANKED | by Melvin", keyed.luma, keyed.chroma, keyed.coverage))
    }

    @Test fun `a poster's treatment is its title block, keyed, once the checks pass`() {
        // A 200 × 300 poster: dark blue, the white title's strokes across its lower part, and a white
        // speck of two pixels above them (a star, an ember).
        val poster = Pixels(200, 300, IntArray(200 * 300) { i ->
            val x = i % 200
            val y = i / 200
            when {
                x in 40 until 160 && y in 220 until 250 -> if ((x / 6) % 2 == 0) 0xFFF4F4F4.toInt() else 0xFF101830.toInt()
                x in 38 until 162 && y in 218 until 252 -> mix(0xFF101830.toInt(), 0xFFF4F4F4.toInt(), ((x + y) % 15 + 1) / 16.0)
                x in 50 until 52 && y == 216 -> 0xFFF4F4F4.toInt()
                else -> 0xFF101830.toInt()
            }
        })
        val lines = listOf(TextLine("TENZING", 0.2, 220 / 300.0, 0.6, 30 / 300.0))
        val cut = cutTreatment("Tenzing", poster, lines) as TreatmentCut.Accepted
        // The block, padded 3% across (6 px) and 1.2% down (3.6 px), widened to whole pixels: 34 to 166, 216 to 254.
        assertEquals(listOf(132, 38), listOf(cut.cutout.width, cut.cutout.height))
        // A stroke is kept; the speck is dropped.
        assertEquals(0xFF, cut.cutout.argb[(235 - 216) * 132 + (40 - 34)] ushr 24)
        assertEquals(0, cut.cutout.argb[(216 - 216) * 132 + (50 - 34)] ushr 24)
        assertEquals(TreatmentCut.Refused(TreatmentVerdict.NotTheName), cutTreatment("Despicable Me", poster, lines))
        assertEquals(TreatmentCut.Refused(TreatmentVerdict.NoText), cutTreatment("Tenzing", poster, emptyList()))
    }

    /** [a] blended toward [b] by [t], 0 to 1. */
    private fun mix(a: Int, b: Int, t: Double): Int {
        fun channel(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()) shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    @Test fun `the lettering's soft edge is part kept`() {
        // A grey ramp from the background up to white: the threshold falls inside it, and the pixels near it are half there.
        val poster = Pixels(60, 60, IntArray(60 * 60) { i ->
            val x = i % 60
            val y = i / 60
            if (x in 10 until 50 && y in 25 until 35) {
                val v = 255 * (x - 10) / 39
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                0xFF000000.toInt()
            }
        })
        val keyed = colourKey(poster)
        val alphas = keyed.cutout.argb.map { it ushr 24 }
        assertTrue(alphas.any { it in 1..254 })
        assertEquals(0, keyed.cutout.argb[30 * 60 + 10] ushr 24)
        assertEquals(0xFF, keyed.cutout.argb[30 * 60 + 49] ushr 24)
    }

    @Test fun `islands far smaller than the lettering are dropped from the cut-out`() {
        // A 60 × 30 cut-out from a 600 px poster: a letter's bar, a 5 × 5 island (a dot, a macron) and a 2 × 2 speck, under the 9 px a speck weighs at most there.
        val opaque = 0xFFF4F4F4.toInt()
        val cutout = Pixels(60, 30, IntArray(60 * 30) { i ->
            val x = i % 60
            val y = i / 60
            when {
                x in 10 until 40 && y in 5 until 25 -> opaque
                x in 50 until 55 && y in 20 until 25 -> opaque
                x in 50 until 52 && y in 10 until 12 -> opaque
                else -> 0x00F4F4F4
            }
        })
        val clean = despeckle(cutout, posterHeight = 600)
        assertEquals(0xFF, clean.argb[10 * 60 + 20] ushr 24)
        assertEquals(0xFF, clean.argb[22 * 60 + 52] ushr 24)
        assertEquals(0, clean.argb[10 * 60 + 50] ushr 24)
        assertEquals(0, clean.argb[11 * 60 + 51] ushr 24)
    }

    @Test fun `the crop takes the box's pixels`() {
        val poster = Pixels(4, 3, IntArray(12) { it })
        val crop = poster.crop(1, 1, 3, 3)
        assertEquals(listOf(5, 6, 9, 10), crop.argb.toList())
    }
}
