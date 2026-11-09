package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' Account section (#434): signed in, "Signed in as …" offers Sign out and "Devices" opens
 * the device list; each opens in place of the column, and Back lands on the row that opened it, or
 * on "Sign in" once this TV signed itself out from the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class AccountSectionTest {
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

    private var email by mutableStateOf<String?>("home@example.com")

    private fun settings(accounts: Boolean = true) = compose.setContent {
        SettingsContent(
            ViewerSettings(listOf("en"), appLanguage = "en", region = "CA", qualityCap = QualityCap.UHD),
            UpdateState.Unknown,
            onChange = {},
            onUpdate = {},
            focusTab = false,
            onTab = {},
            profiles = Profiles(listOf(Profile(Profile.FIRST_ID, name = null)), Profile.FIRST_ID),
            onProfile = {},
            pin = FakePin(),
            catalogLanguages = { listOf("en") },
            catalog = {},
            account = { open, accountRow, devicesRow -> AccountRows(email, open, accountRow, devicesRow) },
            accountPage = { page, _ -> Text("Page: $page") },
            accounts = accounts,
        )
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    @Test fun `signed in, the Account row offers Sign out, and Back returns to it`() {
        settings()
        scrollTo("Signed in as home@example.com")
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.onNodeWithText("Signed in as home@example.com").performClick()
        compose.onNodeWithText("Page: SignOut").assertIsDisplayed()
        back()
        compose.onNodeWithText("Signed in as home@example.com").assertIsFocused()
    }

    @Test fun `Devices opens the device list, and Back returns to it`() {
        settings()
        scrollTo("Devices")
        compose.onNodeWithText("Devices").performClick()
        compose.onNodeWithText("Page: Devices").assertIsDisplayed()
        back()
        compose.onNodeWithText("Devices").assertIsFocused()
    }

    @Test fun `after this TV signs out from the device list, focus lands on Sign in`() {
        settings()
        scrollTo("Devices")
        compose.onNodeWithText("Devices").performClick()
        email = null
        compose.waitForIdle()
        back()
        compose.onNodeWithText("Sign in").assertIsFocused()
    }

    @Test fun `signed out, there is only Sign in`() {
        email = null
        settings()
        scrollTo("Sign in")
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNode(hasText("Devices")).assertDoesNotExist()
    }

    @Test fun `until Accounts can send their Magic link, there is no Account section`() {
        email = null
        settings(accounts = false)
        scrollTo("Catalog")
        compose.onNode(hasText("Account")).assertDoesNotExist()
        compose.onNode(hasText("Sign in")).assertDoesNotExist()
    }
}
