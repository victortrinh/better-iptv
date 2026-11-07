package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.TopTab
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The profile chip and "Who's watching?" (#432): no chip with one Profile, the chip after the
 * Catalog pill with two, Right from Settings onto it, OK opening the switcher on the current
 * Profile, a pick switching and closing, Back closing, the chip's name following a switch and a
 * rename, and "Add a profile" opening Settings' add flow.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class ProfileChipTest {
    @get:Rule(order = 0)
    val host = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = context.packageName })
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val first = Profile.FIRST_ID
    private val second = Profile.FIRST_ID + 1
    private val one = Profiles(listOf(Profile(first, name = null)), first)
    private val two = Profiles(listOf(Profile(first, name = null), Profile(second, "Alex", kids = true)), first)

    private var profiles by mutableStateOf(two)
    private val switches = mutableListOf<Long>()
    private var adds = 0

    /** The tabs with a stand-in Catalog pill 100 dp wide, and the chip for [shown]. */
    private fun tabs(shown: Profiles) = compose.setContent {
        val chip = remember(shown) { ProfileChipState(shown, onOpen = {}, FocusRequester()) }
        Box(Modifier.fillMaxSize()) {
            TopTabs(listOf(TopTab("Home"), TopTab("Movies")), selected = 0, onSelect = {}, modifier = Modifier.testTag("tabs")) {
                TabsEnd(chip, pill = { Box(Modifier.size(100.dp, 20.dp).testTag("pill")) })
            }
        }
    }

    /** Settings under the switcher, as the app draws them, then Up from the current Profile's row to the Settings tab. */
    private fun settings() {
        compose.setContent {
            var adding by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                ProfileSwitcher(
                    profiles,
                    FakePin(),
                    onSwitch = { id ->
                        switches += id
                        profiles = Profiles(profiles.all, id)
                    },
                    onAdd = {
                        adds++
                        adding = true
                    },
                ) {
                    SettingsContent(
                        ViewerSettings(listOf("en"), appLanguage = "en", region = "CA", qualityCap = QualityCap.UHD),
                        UpdateState.Unknown,
                        onChange = {},
                        onUpdate = {},
                        focusTab = false,
                        onTab = {},
                        profiles = profiles,
                        onProfile = {},
                        pin = FakePin(),
                        catalogLanguages = { listOf("en") },
                        catalog = {},
                        addProfile = adding,
                        onAddProfileShown = { adding = false },
                    )
                }
            }
        }
        compose.waitForIdle()
        press(listOf(Key.DirectionUp))
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun chip() = compose.onNodeWithTag(PROFILE_CHIP_TAG)

    @Test fun theChipPulsesOneCycleWhenDueThenSaysSo() {
        var pulsed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val chip = remember { ProfileChipState(two, onOpen = {}, FocusRequester(), pulse = true, onPulsed = { pulsed++ }) }
            Box(Modifier.fillMaxSize()) { ProfileChip(chip) }
        }
        // The waiting dot's rhythm, once: 900 ms down to 30%, 900 ms back.
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(0, pulsed)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, pulsed)
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(1, pulsed)
    }

    @Test fun theChipDoesNotPulseWhenNotDue() {
        var pulsed = 0
        compose.setContent {
            val chip = remember { ProfileChipState(two, onOpen = {}, FocusRequester(), onPulsed = { pulsed++ }) }
            Box(Modifier.fillMaxSize()) { ProfileChip(chip) }
        }
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(0, pulsed)
    }

    @Test fun withOneProfileNoChipIsDrawnAndThePillEndsTheTabs() {
        tabs(one)

        chip().assertDoesNotExist()
        val bar = compose.onNodeWithTag("tabs").getUnclippedBoundsInRoot()
        assertEquals(bar.right, compose.onNodeWithTag("pill").getUnclippedBoundsInRoot().right)
    }

    @Test fun withTwoTheChipEndsTheTabsAndThePillShiftsLeftOfIt() {
        tabs(two)

        val bar = compose.onNodeWithTag("tabs").getUnclippedBoundsInRoot()
        val chip = chip().getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag("pill").getUnclippedBoundsInRoot()
        assertEquals(bar.right, chip.right)
        assertTrue("The pill (${pill.right}) sits left of the chip (${chip.left})", pill.right < chip.left)
        // The current Profile's name: the first one is named after the device's language until renamed.
        chip().assert(hasText("English"))
    }

    @Test fun rightFromSettingsFocusesTheChipAndOkOpensTheSwitcherOnTheCurrentProfile() {
        settings()
        // Up from the screen lands on the selected tab, not on the chip beside the tabs.
        compose.onNodeWithContentDescriptionOrText("Settings").assertIsFocused()

        press(listOf(Key.DirectionRight))
        chip().assertIsFocused()
        press(listOf(Key.DirectionCenter))

        compose.onNodeWithText("Who's watching?").assertExists()
        compose.onNodeWithTag("switcher:$first").assertIsFocused()
        compose.onNodeWithTag("switcher:$first").assert(hasText(CHOSEN))
        // A Kids profile carries its label; "Add a profile" ends the list.
        compose.onNodeWithTag("switcher:$second").assert(hasText("Alex") and hasText("Kids"))
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        compose.onNodeWithTag("switcher:add").assertIsFocused()
        // Focus stays in the list.
        press(listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionRight))
        compose.onNodeWithTag("switcher:$first").assertIsFocused()
    }

    @Test fun pickingAnotherProfileSwitchesClosesAndTheChipNamesIt() {
        settings()
        press(listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown, Key.DirectionCenter))

        assertEquals(listOf(second), switches)
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
        chip().assert(hasText("Alex"))
        chip().assertIsFocused()
    }

    @Test fun pickingTheCurrentProfileOnlyCloses() {
        settings()
        press(listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionCenter))

        assertEquals(emptyList<Long>(), switches)
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
        chip().assertIsFocused()
    }

    @Test fun backClosesWithoutSwitching() {
        settings()
        press(listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown))
        back()

        assertEquals(emptyList<Long>(), switches)
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
        chip().assert(hasText("English"))
        chip().assertIsFocused()
    }

    @Test fun theChipFollowsARename() {
        settings()
        chip().assert(hasText("English"))

        profiles = Profiles(profiles.all.map { if (it.id == first) it.copy(name = "Sam") else it }, first)
        compose.waitForIdle()

        chip().assert(hasText("Sam"))
    }

    @Test fun addAProfileOpensSettingsAddFlow() {
        settings()
        press(listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))

        assertEquals(1, adds)
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
        // The name page, the keyboard's first letter focused, as Settings' "Add a profile" opens it.
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("a").assertIsFocused()
        back()
        compose.onNode(hasText("Add a profile")).assertIsFocused()
    }

    @Test fun deletedDownToOneTheChipGoes() {
        settings()
        chip().assertExists()

        profiles = Profiles(listOf(Profile(first, name = null)), first)
        compose.waitForIdle()

        chip().assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescriptionOrText(name: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(name) or hasText(name))
}
