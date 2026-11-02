package dev.betteriptv.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The player's picture sits on pure black, not the near-black ground (#273). */
class ColorsTest {
    @Test
    fun pictureIsOpaquePureBlackAndNotTheGround() {
        assertEquals(Color(0xFF000000), Colors.picture)
        assertNotEquals(Colors.ground, Colors.picture)
    }
}
