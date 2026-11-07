package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' Profiles section (#428): the household's Profiles first in Settings, each opening its
 * page (Watch as, Rename, Delete), "Add a profile" typed on the remote keyboard, deleting after a
 * question and never the last one, and where focus lands on each. The household here is kept in
 * memory, as the library would keep it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class ProfilesSettingsTest {
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

    private var profiles by mutableStateOf(Profiles(listOf(Profile(Profile.FIRST_ID, name = null)), Profile.FIRST_ID))
    private var nextId = Profile.FIRST_ID + 1
    private val changes = mutableListOf<ProfileChange>()
    // The household has its PIN already: adding a Kids profile then asks none (HouseholdPinTest has the first one).
    private val pin = FakePin("2580")

    private fun change(change: ProfileChange) {
        changes += change
        profiles = when (change) {
            is ProfileChange.Add -> Profiles(profiles.all + Profile(nextId++, change.name, change.kids), profiles.currentId)
            is ProfileChange.Rename -> Profiles(profiles.all.map { if (it.id == change.id) it.copy(name = change.name) else it }, profiles.currentId)
            is ProfileChange.Switch -> Profiles(profiles.all, change.id)
            is ProfileChange.Delete -> {
                val left = profiles.all.filter { it.id != change.id }
                Profiles(left, if (change.id == profiles.currentId) left.first().id else profiles.currentId)
            }
        }
    }

    private fun settings() = compose.setContent {
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

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun row(text: String) = compose.onNode(hasText(text), useUnmergedTree = false)

    private fun type(name: String) = name.lowercase().forEach {
        if (it == ' ') compose.onNodeWithContentDescription("Space").performClick() else compose.onNodeWithText(it.toString()).performClick()
    }

    /** Profile [id]'s row on the list. */
    private fun profileRow(id: Long) = compose.onNodeWithTag("profile:$id")

    private val first = Profile.FIRST_ID
    private val second = Profile.FIRST_ID + 1

    @Test fun theOneProfileIsNamedAfterTheDevicesLanguageAndTakesFirstFocus() {
        settings()

        compose.onNodeWithText("Profiles").assertExists()
        // Robolectric's device speaks English: the first Profile is "English" until renamed, marked as the one the TV is on.
        profileRow(first).assertIsFocused()
        profileRow(first).assert(hasText("English") and hasText(CHOSEN))
        press(listOf(Key.DirectionDown))
        row("Add a profile").assertIsFocused()
        // Languages follow, as before Profiles.
        press(listOf(Key.DirectionDown))
        row("App language").assertExists()
    }

    @Test fun addingAProfileTypesItsNameOnTheRemoteKeyboard() {
        settings()
        press(listOf(Key.DirectionDown, Key.DirectionCenter))

        // The name page: its title, an empty name, and focus on the keyboard's first letter.
        compose.onNodeWithText("Add a profile").assertExists()
        compose.onNodeWithText("a").assertIsFocused()
        type("alex ma")
        compose.onNodeWithText("Alex Ma").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(listOf<ProfileChange>(ProfileChange.Add("Alex Ma")), changes)
        // Back on the list, on "Add a profile", the new Profile above it; the TV stays on the first.
        row("Add a profile").assertIsFocused()
        profileRow(second).assert(hasText("Alex Ma") and !hasText(CHOSEN))
        profileRow(first).assert(hasText(CHOSEN))
    }

    @Test fun addingAProfileOffersTheKidsSwitchOffUnlessChosen() {
        settings()
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        type("sam")
        compose.onNodeWithText("Kids profile").assertExists()
        compose.onNodeWithText("On").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(listOf<ProfileChange>(ProfileChange.Add("Sam", kids = true)), changes)
    }

    @Test fun renamingAProfileOffersNoKidsSwitch() {
        settings()
        press(listOf(Key.DirectionCenter))
        row("Rename").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Rename English").assertExists()
        compose.onNodeWithText("Kids profile").assertDoesNotExist()
    }

    @Test fun aKidsProfilesSettingsOfferTheProfileSwitchAndTextSizeOnly() {
        change(ProfileChange.Add("Sam", kids = true))
        change(ProfileChange.Switch(second))
        changes.clear()
        settings()

        profileRow(second).assertIsFocused()
        compose.onNodeWithText("Text size").assertExists()
        listOf("Add a profile", "App language", "Content languages", "Region", "Leagues", "Quality cap", "Account", "Catalog", "App version").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        // OK on another Profile switches to it once the Household PIN is typed: there is no page to rename or delete from.
        press(listOf(Key.DirectionUp, Key.DirectionCenter))
        "2580".forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.waitForIdle()
        assertEquals(listOf<ProfileChange>(ProfileChange.Switch(first)), changes)
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun backFromTheNamePageAddsNothing() {
        settings()
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        type("al")
        back()

        assertEquals(emptyList<ProfileChange>(), changes)
        row("Add a profile").assertIsFocused()
    }

    @Test fun aProfilesPageSwitchesToItAndFocusReturnsToItsRow() {
        change(ProfileChange.Add("Alex"))
        settings()
        press(listOf(Key.DirectionDown))
        profileRow(second).assertIsFocused()
        press(listOf(Key.DirectionCenter))

        // Its page: Watch as, which takes focus, Rename and Delete profile.
        row("Watch as Alex").assertIsFocused()
        row("Rename").assertExists()
        row("Delete profile").assertExists()
        press(listOf(Key.DirectionCenter))

        assertEquals(second, profiles.currentId)
        profileRow(second).assertIsFocused()
        profileRow(second).assert(hasText(CHOSEN))
        profileRow(first).assert(!hasText(CHOSEN))
    }

    @Test fun theCurrentProfilesPageOpensOnRenameAndRenamesIt() {
        settings()
        press(listOf(Key.DirectionCenter))

        compose.onNodeWithText("Watch as English").assertDoesNotExist()
        row("Rename").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        compose.onNodeWithText("Rename English").assertExists()
        compose.onNodeWithText("a").assertIsFocused()
        type("sam")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(listOf<ProfileChange>(ProfileChange.Rename(Profile.FIRST_ID, "Sam")), changes)
        // Back on the page, now named Sam, then on the list on its row.
        compose.onNodeWithText("Sam").assertExists()
        back()
        profileRow(first).assertIsFocused()
        profileRow(first).assert(hasText("Sam"))
    }

    @Test fun deletingAsksFirstWithCancelFocused() {
        change(ProfileChange.Add("Alex"))
        changes.clear()
        settings()
        press(listOf(Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown, Key.DirectionDown))
        row("Delete profile").assertIsFocused()
        press(listOf(Key.DirectionCenter))

        compose.onNodeWithText("Delete Alex?").assertExists()
        compose.onNodeWithText("Their Continue watching, Favourites and settings are deleted from this TV.").assertExists()
        row("Cancel").assertIsFocused()
        // Cancel goes back to the page, deleting nothing.
        press(listOf(Key.DirectionCenter))
        row("Watch as Alex").assertExists()
        assertEquals(emptyList<ProfileChange>(), changes)

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown))
        row("Delete").assertIsFocused()
        press(listOf(Key.DirectionCenter))

        assertEquals(listOf<ProfileChange>(ProfileChange.Delete(Profile.FIRST_ID + 1)), changes)
        profileRow(second).assertDoesNotExist()
        profileRow(first).assertIsFocused()
    }

    @Test fun theLastProfilesDeleteSaysWhyAndDoesNothing() {
        settings()
        press(listOf(Key.DirectionCenter, Key.DirectionDown))

        row("Delete profile").assertIsFocused()
        compose.onNodeWithText("The last profile can't be deleted").assertExists()
        press(listOf(Key.DirectionCenter))
        row("Delete profile").assertIsFocused()
        assertEquals(emptyList<ProfileChange>(), changes)
    }

    @Test fun aFullHouseholdCantAddASixth() {
        repeat(Library.MAX_PROFILES - 1) { change(ProfileChange.Add("P$it")) }
        changes.clear()
        settings()

        compose.onNodeWithText("Up to 5 profiles").assertExists()
        press(List(Library.MAX_PROFILES) { Key.DirectionDown })
        row("Add a profile").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        row("Add a profile").assertIsFocused()
        assertEquals(emptyList<ProfileChange>(), changes)
    }
}
