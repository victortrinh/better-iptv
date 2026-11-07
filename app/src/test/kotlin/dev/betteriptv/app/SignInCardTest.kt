package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.SignInCode
import dev.betteriptv.core.Unreachable
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The first Sync's sign-in card (#438), with a scripted sign-in: under the checklist with a live code
 * and QR, "Signed in as …" once the phone signs the TV in, gone on Back or Home and never offered
 * again, and none on a later Sync or a device that isn't offered it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class SignInCardTest {
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

    private val code = SignInCode("K7M2QX", "https://zugig.app/sign-in?code=K7M2QX", Duration.ofMinutes(10))
    private var signedInAs: String? = null
    private var codesAsked = 0
    private var dismissals = 0
    private var firstSync by mutableStateOf(true)

    private fun show(offered: Boolean = true, status: SyncStatus = SyncStatus.NeverSynced(null)) {
        compose.setContent {
            val signIn = firstSyncSignIn(
                offered = offered,
                firstSync = firstSync,
                start = { codesAsked++; code },
                check = { signedInAs },
                onDismiss = { dismissals++ },
            )
            SyncChecklist(status, SyncStep.Channels, onRetry = {}, card = signIn?.let { { SignInCard(it) } })
        }
    }

    @Test fun `under the checklist, a live code and QR, then "Signed in as" once the phone signs in`() {
        show()
        compose.onNodeWithText("Setting up your catalog").assertIsDisplayed()
        compose.onNodeWithText("Sign in to Zugig to keep your history across devices").assertIsDisplayed()
        compose.onNodeWithTag("sign-in-code").assertTextEquals("K7M 2QX")
        compose.onNodeWithContentDescription("QR code for the sign-in page").assertIsDisplayed()
        compose.onNodeWithText("Waiting for your phone…").assertIsDisplayed()

        signedInAs = "home@example.com"
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithText("Signed in as home@example.com").assertIsDisplayed()
        assertEquals(1, codesAsked)
        assertEquals(0, dismissals)
    }

    @Test fun `Back dismisses it, the checklist stays`() {
        show()
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Setting up your catalog").assertIsDisplayed()
        assertEquals(1, dismissals)
    }

    @Test fun `Home dismisses it`() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test fun `it goes when the first Sync ends`() {
        show()
        firstSync = false
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertDoesNotExist()
        assertEquals(0, dismissals)
    }

    @Test fun `none on a device not offered it, already signed in or dismissed before`() {
        show(offered = false)
        compose.onNodeWithText("Setting up your catalog").assertIsDisplayed()
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertDoesNotExist()
        assertEquals(0, codesAsked)
    }

    @Test fun `none on a later Sync`() {
        firstSync = false
        show()
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertDoesNotExist()
        assertEquals(0, codesAsked)
    }

    @Test fun `a failed first Sync keeps Retry's focus beside the card`() {
        show(status = SyncStatus.Failed(Unreachable("x"), null, null, SyncStep.Channels))
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithTag(SIGN_IN_CARD_TAG).assertIsDisplayed()
    }
}
