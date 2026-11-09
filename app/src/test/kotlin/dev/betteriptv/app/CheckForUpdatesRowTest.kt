package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.AppUpdate
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

/** Settings' App version row checks for a newer build when none is on offer, and says how the check went. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class CheckForUpdatesRowTest {
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

    private var update by mutableStateOf<UpdateState>(UpdateState.Unknown)
    private var checking by mutableStateOf(false)
    private var checks = 0
    private var updates = 0

    private fun settings() = compose.setContent {
        SettingsContent(
            ViewerSettings(listOf("en"), appLanguage = "en", region = "CA", qualityCap = QualityCap.UHD),
            update,
            onChange = {},
            onUpdate = { updates++ },
            focusTab = false,
            onTab = {},
            profiles = Profiles(listOf(Profile(Profile.FIRST_ID, name = null)), Profile.FIRST_ID),
            onProfile = {},
            pin = FakePin(),
            catalogLanguages = { listOf("en") },
            catalog = {},
            checkingUpdates = checking,
            onCheckUpdates = { checks++ },
        )
    }

    private fun press(text: String) {
        shown(text)
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun shown(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private val version = BuildConfig.VERSION_NAME

    @Test fun `pressed with nothing on offer, it checks, then says it's up to date`() {
        settings()
        press(version)
        assertEquals(1, checks)
        checking = true
        shown("Checking for updates…")
        // A second press while checking doesn't check twice.
        press("Checking for updates…")
        assertEquals(1, checks)
        update = UpdateState.UpToDate
        checking = false
        shown("$version · Up to date")
        press("$version · Up to date")
        assertEquals(2, checks)
    }

    @Test fun `a check that couldn't read the manifest says so`() {
        settings()
        press(version)
        checking = true
        compose.waitForIdle()
        checking = false
        shown("$version · Couldn't check")
    }

    @Test fun `a build on offer is installed, not checked for`() {
        update = UpdateState.Available(AppUpdate(95, "0.1.95", "https://example.com/a.apk", "0".repeat(64), 1))
        settings()
        press("Update to 0.1.95")
        assertEquals(1, updates)
        assertEquals(0, checks)
    }

    @Config(qualifiers = "fr")
    @Test fun `in French`() {
        settings()
        press(version)
        checking = true
        shown("Recherche de mises à jour…")
    }
}
