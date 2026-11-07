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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.ProfileChoice
import dev.betteriptv.core.ProfileToKeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Keep which profiles?" (#440): signing in past five Profiles asks which to keep before it completes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class KeepProfilesTest {
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

    /** The Account's four (one of them merged with this TV's unnamed one), and this TV's Kim, which it is on, and Lou. */
    private val choice = ProfileChoice(
        listOf(
            ProfileToKeep("a", null, kids = false, onThisTv = true, inAccount = true, current = false),
            ProfileToKeep("b", "Sam", kids = false, onThisTv = false, inAccount = true, current = false),
            ProfileToKeep("c", "Mia", kids = true, onThisTv = false, inAccount = true, current = false),
            ProfileToKeep("d", "Jo", kids = false, onThisTv = false, inAccount = true, current = false),
            ProfileToKeep("e", "Kim", kids = false, onThisTv = true, inAccount = false, current = true),
            ProfileToKeep("f", "Lou", kids = false, onThisTv = true, inAccount = false, current = false),
        ),
        most = 5,
    )
    private var kept: Set<String>? = null
    private var cancelled = false

    /** Shows it over a screen already up, as sign-in completing does: the window has focus to give. */
    private fun show() {
        var asked by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                if (asked) KeepProfiles(choice, onKeep = { kept = it }, onCancel = { cancelled = true })
            }
        }
        compose.waitForIdle()
        asked = true
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun everyProfileIsListedWithWhereItIsAndKeepWaitsUntilFiveAreTicked() {
        show()
        compose.onNodeWithText("Keep which profiles?").assertExists()
        compose.onNodeWithTag("keep:a").assertIsFocused()
        compose.onNodeWithText("On this TV and in your account").assertExists()
        compose.onNodeWithText("In your account · Kids").assertExists()
        compose.onNodeWithText("Watching now, on this TV").assertExists()
        compose.onNodeWithText("On this TV").assertExists()
        compose.onNodeWithText("Untick 1 more.").assertExists()

        compose.onNodeWithTag("keep:done").performScrollTo().performClick()
        assertNull(kept)

        // Down to Jo, OK unticks it.
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        compose.onNodeWithText("Untick 1 more.").assertDoesNotExist()
        compose.onNodeWithTag("keep:done").performScrollTo().performClick()
        assertEquals(setOf("a", "b", "c", "e", "f"), kept)
    }

    @Test fun theProfileTheTvIsOnStaysTicked() {
        show()
        compose.onNodeWithTag("keep:e").performScrollTo().performClick()
        compose.onNodeWithTag("keep:f").performScrollTo().performClick()
        compose.onNodeWithTag("keep:done").performScrollTo().performClick()
        assertEquals(setOf("a", "b", "c", "d", "e"), kept)
    }

    @Test fun backLeavesTheTvSignedOut() {
        show()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(cancelled)
        cancelled = false
        compose.onNodeWithText("Don't sign in").performScrollTo().performClick()
        assertTrue(cancelled)
    }
}
