package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.PIN_DOTS_TAG
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Household PIN on screen (#437): the digit pad and its dots, chosen twice with the first Kids
 * profile, asked before a Kids profile is left from Settings or from the chip, locked after five
 * wrong tries with a countdown, and "Change PIN" outside a Kids profile.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class HouseholdPinPagesTest {
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
    private var profiles by mutableStateOf(Profiles(listOf(Profile(first, name = null)), first))
    private var nextId = first + 1
    private val changes = mutableListOf<ProfileChange>()

    private fun change(change: ProfileChange) {
        changes += change
        profiles = when (change) {
            is ProfileChange.Add -> Profiles(profiles.all + Profile(nextId++, change.name, change.kids), profiles.currentId)
            is ProfileChange.Switch -> Profiles(profiles.all, change.id)
            else -> error("Not asked here: $change")
        }
    }

    /** A household of the first Profile and Mia, a Kids profile, on [on]. */
    private fun withKids(on: Long) {
        profiles = Profiles(listOf(Profile(first, name = null), Profile(second, "Mia", kids = true)), on)
        nextId = second + 1
    }

    private fun settings(pin: FakePin) = compose.setContent {
        SettingsContent(
            ViewerSettings(listOf("en"), appLanguage = "en", region = "CA", qualityCap = QualityCap.UHD),
            UpdateState.Unknown,
            onChange = {},
            onUpdate = {},
            focusTab = false,
            onTab = {},
            profiles = profiles,
            onProfile = ::change,
            pin = pin,
            catalogLanguages = { listOf("en") },
            catalog = {},
        )
    }

    /** Settings under the chip's switcher, as the app draws them, with "Who's watching?" open. */
    private fun switcher(pin: FakePin) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ProfileSwitcher(profiles, pin, onSwitch = { change(ProfileChange.Switch(it)) }, onAdd = {}) {
                    SettingsContent(
                        ViewerSettings(listOf("en"), appLanguage = "en", region = "CA", qualityCap = QualityCap.UHD),
                        UpdateState.Unknown, onChange = {}, onUpdate = {}, focusTab = false, onTab = {},
                        profiles = profiles, onProfile = ::change, pin = pin, catalogLanguages = { listOf("en") }, catalog = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // Up from the current Profile's row to the Settings tab, Right to the chip, OK.
        repeat(profiles.all.indexOf(profiles.current) + 1) { press(listOf(Key.DirectionUp)) }
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        compose.onNodeWithText("Who's watching?").assertExists()
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun key(digit: Char) = compose.onNode(hasText(digit.toString()) and hasClickAction())

    private fun type(digits: String) {
        digits.forEach { key(it).performClick() }
        compose.waitForIdle()
    }

    private fun dots(entered: Int) = compose.onNodeWithTag(PIN_DOTS_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("$entered of 4")))

    /** "Add a profile" from the list, typing [name], the Kids switch on, then Save. */
    private fun addKids(name: String) {
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        name.forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.onNodeWithText("On").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
    }

    @Test fun theFirstKidsProfileChoosesThePinOnAPadTheDpadDrives() {
        val pin = FakePin()
        settings(pin)
        addKids("mia")

        compose.onNodeWithText("Choose a Household PIN").assertExists()
        // Focus lands on 1; the grid is a phone's, 0 under 7 and Delete beside 0.
        key('1').assertIsFocused()
        dots(0)
        press(listOf(Key.DirectionCenter))
        dots(1)
        press(listOf(Key.DirectionRight, Key.DirectionDown, Key.DirectionCenter))
        key('5').assertIsFocused()
        dots(2)
        press(listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
        compose.onNodeWithContentDescription("Delete").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        dots(1)
        press(listOf(Key.DirectionLeft))
        key('0').assertIsFocused()
        assertEquals(emptyList<ProfileChange>(), changes)

        // 1, then 0, 8, 0: typed twice, the Profile is made with its PIN.
        press(listOf(Key.DirectionCenter, Key.DirectionUp, Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown, Key.DirectionLeft, Key.DirectionCenter))
        compose.onNodeWithText("Enter it again").assertExists()
        dots(0)
        type("1080")

        assertEquals("1080", pin.pin)
        assertEquals(listOf<ProfileChange>(ProfileChange.Add("Mia", kids = true)), changes)
        compose.onNodeWithText("Add a profile").assertIsFocused()
    }

    @Test fun aSecondPinThatDoesntMatchStartsOver() {
        val pin = FakePin()
        settings(pin)
        addKids("mia")
        type("1234")
        type("1243")

        compose.onNodeWithText("Those didn't match. Choose a PIN again.").assertExists()
        compose.onNodeWithText("Choose a Household PIN").assertExists()
        assertNull(pin.pin)
        assertEquals(emptyList<ProfileChange>(), changes)
        type("1243")
        type("1243")
        assertEquals("1243", pin.pin)
    }

    @Test fun backFromChoosingThePinKeepsTheNameAndMakesNoProfile() {
        settings(FakePin())
        addKids("mia")
        back()

        compose.onNodeWithText("Mia").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Choose a Household PIN").assertExists()
        assertEquals(emptyList<ProfileChange>(), changes)
    }

    @Test fun laterKidsProfilesReuseThePin() {
        withKids(on = first)
        settings(FakePin("2580"))
        press(listOf(Key.DirectionDown))
        addKids("leo")

        compose.onNodeWithText("Choose a Household PIN").assertDoesNotExist()
        assertEquals(listOf<ProfileChange>(ProfileChange.Add("Leo", kids = true)), changes)
    }

    @Test fun fromAKidsProfileAnotherProfileTakesThePinAndBackStays() {
        withKids(on = second)
        settings(FakePin("2580"))
        press(listOf(Key.DirectionUp, Key.DirectionCenter))

        compose.onNodeWithText("Household PIN").assertExists()
        compose.onNodeWithText("Enter it to watch as English").assertExists()
        key('1').assertIsFocused()
        type("1111")
        compose.onNodeWithText("Wrong PIN. 4 tries left").assertExists()
        dots(0)
        back()

        assertEquals(emptyList<ProfileChange>(), changes)
        compose.onNodeWithTag("profile:$first").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        type("2580")
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
    }

    @Test fun fiveWrongTriesLockThePadForThirtySecondsWithACountdown() {
        withKids(on = second)
        val pin = FakePin("2580")
        settings(pin)
        press(listOf(Key.DirectionUp, Key.DirectionCenter))
        compose.mainClock.autoAdvance = false
        repeat(5) {
            type("0000")
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Too many wrong tries. Try again in 30 s").assertExists()
        // Locked, the keys keep focus and take no digit.
        type("2580")
        dots(0)
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Too many wrong tries. Try again in 20 s").assertExists()
        pin.locked = null
        compose.mainClock.advanceTimeBy(20_000)
        compose.waitForIdle()
        compose.onNodeWithText("Too many wrong tries", substring = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true

        type("2580")
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
    }

    @Test fun changePinAsksTheOldOneThenTheNewOneTwice() {
        val pin = FakePin("2580")
        settings(pin)
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        compose.onNodeWithText("Change PIN").assertIsFocused()
        press(listOf(Key.DirectionCenter))

        compose.onNodeWithText("Enter the current PIN").assertExists()
        type("1397")
        compose.onNodeWithText("Wrong PIN. 4 tries left").assertExists()
        type("2580")
        compose.onNodeWithText("Choose a new PIN").assertExists()
        type("1397")
        compose.onNodeWithText("Enter it again").assertExists()
        type("1397")

        assertEquals("1397", pin.pin)
        compose.onNodeWithText("Change PIN").assertIsFocused()
    }

    @Test fun changePinIsOfferedOnlyOutsideAKidsProfileOnceThereIsAPin() {
        settings(FakePin())
        compose.onNodeWithText("Change PIN").assertDoesNotExist()
    }

    @Test fun aKidsProfileIsNotOfferedChangePin() {
        withKids(on = second)
        settings(FakePin("2580"))
        compose.onNodeWithText("Text size").assertExists()
        compose.onNodeWithText("Change PIN").assertDoesNotExist()
    }

    @Test fun afterAResetFromThePhoneLeavingAKidsProfileChoosesANewPin() {
        // "Reset the Household PIN" cleared it on every TV of the Account (#440).
        withKids(on = second)
        val pin = FakePin()
        settings(pin)
        press(listOf(Key.DirectionUp, Key.DirectionCenter))

        compose.onNodeWithText("Choose a new PIN").assertExists()
        compose.onNodeWithText("The Household PIN was reset. Choose a new one to leave the Kids profile.").assertExists()
        type("4321")
        type("4321")
        assertEquals("4321", pin.pin)
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
    }

    @Test fun afterAResetTheChipsSwitchChoosesANewPinToo() {
        withKids(on = second)
        val pin = FakePin()
        switcher(pin)
        press(listOf(Key.DirectionUp, Key.DirectionCenter))
        compose.onNodeWithText("Choose a new PIN").assertExists()
        type("4321")
        type("4321")
        assertEquals("4321", pin.pin)
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
    }

    @Test fun fromTheChipAKidsProfileTakesThePinAndBackReturnsToTheList() {
        withKids(on = second)
        switcher(FakePin("2580"))
        // No "Add a profile" from a Kids profile, as in its Settings.
        compose.onNodeWithTag("switcher:add").assertDoesNotExist()
        press(listOf(Key.DirectionUp, Key.DirectionCenter))

        compose.onNodeWithText("Enter it to watch as English").assertExists()
        key('1').assertIsFocused()
        back()
        compose.onNodeWithText("Who's watching?").assertExists()
        compose.onNodeWithTag("switcher:$second").assertIsFocused()
        assertEquals(emptyList<ProfileChange>(), changes)

        press(listOf(Key.DirectionUp, Key.DirectionCenter))
        type("2580")
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
    }

    @Test fun fromAnotherProfileTheChipSwitchesToAKidsProfileWithoutThePin() {
        withKids(on = first)
        switcher(FakePin("2580"))
        press(listOf(Key.DirectionDown, Key.DirectionCenter))

        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(second)), changes)
        compose.onNodeWithTag(dev.betteriptv.app.PIN_PAGE_TAG).assertDoesNotExist()
    }
}
