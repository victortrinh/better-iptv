package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A live Channel's Tracks on VLC, known only by the names its player gives them. */
class VlcNamedTracksTest {
    @Test fun `audio named with a language is in that language`() {
        assertEquals(Track("en"), vlcNamedTrack("Track 1 - [English]"))
        assertEquals(Track("fr"), vlcNamedTrack("Track 2 - Commentaire - [French]"))
    }

    @Test fun `a Track named without a language has none`() {
        assertEquals(Track(null), vlcNamedTrack("Track 1"))
        assertEquals(Track(null), vlcNamedTrack("Track 1 - [Klingonese]"))
    }

    @Test fun `the first closed captions are CEA-608 captions, and the empty channels after them are left out`() {
        assertEquals(Track(null, CEA_608), vlcNamedTrack("Closed captions 1"))
        assertNull(vlcNamedTrack("Closed captions 2"))
        assertNull(vlcNamedTrack("Closed captions 4"))
    }

}
