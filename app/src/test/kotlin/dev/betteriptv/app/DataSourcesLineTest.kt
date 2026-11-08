package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/** Settings' App section names where the Title facts and Trending come from (#480), in both App languages. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class DataSourcesLineTest {
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

    private fun settings() = compose.setContent {
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
            account = { _, _, _ -> },
            accountPage = { _, _ -> },
        )
    }

    private fun shown(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun `in English`() {
        settings()
        shown("Data from Wikidata and Wikipedia")
    }

    @Config(qualifiers = "fr")
    @Test fun `in French`() {
        settings()
        shown("Données de Wikidata et Wikipédia")
    }
}
