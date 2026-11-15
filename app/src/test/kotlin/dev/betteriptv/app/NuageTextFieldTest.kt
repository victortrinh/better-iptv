package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.NuageTextField
import kotlinx.coroutines.awaitCancellation
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
 * A form's text fields under a remote: the D-pad walks them without the on-screen keyboard, which
 * would take the D-pad for its own keys, and OK is what opens it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class NuageTextFieldTest {
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

    private var focused = ""

    /** Each time a field asks the system for its keyboard. */
    private var keyboards = 0

    private fun form() {
        val first = FocusRequester()
        compose.setContent {
            val typing = remember { mutableStateOf(false) }
            fun at(name: String) = Modifier.onFocusChanged { if (it.hasFocus) focused = name }
            InterceptPlatformTextInput({ _, _ -> keyboards++; awaitCancellation() }) {
                Column {
                    NuageTextField("", {}, "Address", at("address").focusRequester(first), typing = typing)
                    NuageTextField("", {}, "Username", at("username"), typing = typing)
                    NuageTextField("", {}, "Password", at("password"), typing = typing, onDone = {})
                    NuageButton("Log in", {}, at("button"))
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun theDpadWalksTheFieldsAndTheButtonWithoutTheKeyboard() {
        form()
        assertEquals("address", focused)

        press(Key.DirectionDown)
        assertEquals("username", focused)
        press(Key.DirectionDown)
        assertEquals("password", focused)
        press(Key.DirectionDown)
        assertEquals("button", focused)
        press(Key.DirectionUp)
        assertEquals("password", focused)

        assertEquals(0, keyboards)
    }

    @Test
    fun okOpensTheKeyboardOnTheFocusedField() {
        form()

        press(Key.DirectionCenter)

        assertEquals("address", focused)
        assertEquals(1, keyboards)
    }

    @Test
    fun leavingAFieldWithTheDpadClosesTheKeyboard() {
        form()
        press(Key.DirectionCenter)

        press(Key.DirectionDown)

        assertEquals("username", focused)
        assertEquals(1, keyboards)
    }
}
