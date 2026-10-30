package dev.betteriptv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class BackToTabTest {
    @Test
    fun backMovesToTheTabOnRelease() {
        assertEquals(BackKey.MoveToTab, backToTab(KeyEventType.KeyUp, Key.Back))
    }

    @Test
    fun theBackPressIsHeldSoItNeverReachesTheSystem() {
        assertEquals(BackKey.Hold, backToTab(KeyEventType.KeyDown, Key.Back))
    }

    @Test
    fun otherKeysGoOnToTheCards() {
        assertEquals(BackKey.Pass, backToTab(KeyEventType.KeyDown, Key.DirectionDown))
        assertEquals(BackKey.Pass, backToTab(KeyEventType.KeyUp, Key.DirectionCenter))
        assertEquals(BackKey.Pass, backToTab(KeyEventType.KeyDown, Key.Menu))
    }
}
