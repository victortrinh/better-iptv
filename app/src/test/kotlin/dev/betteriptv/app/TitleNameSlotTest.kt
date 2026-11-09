package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.TITLE_TREATMENT
import dev.betteriptv.app.ui.components.TitleNameSlot
import dev.betteriptv.app.ui.theme.NuageTheme
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
 * The name at the top of the hero and the Title page (ADR 0016): its Title treatment, else its name in
 * Display type, in a slot two Display lines tall, so the metadata line under it sits in the same place
 * whichever is shown and however long the name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TitleNameSlotTest {
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

    private var name by mutableStateOf("Soul")
    private var treatment by mutableStateOf<Painter?>(null)

    /** A cut-out [width] × [height] pixels. */
    private fun cutout(width: Int, height: Int): Painter = object : Painter() {
        override val intrinsicSize = Size(width.toFloat(), height.toFloat())
        override fun DrawScope.onDraw() = drawRect(Color.White)
    }

    private fun metadataTop(): Float {
        compose.waitForIdle()
        return compose.onNodeWithTag("metadata").getBoundsInRoot().top.value
    }

    @Test fun theMetadataLineStaysPutWithATreatmentOrWithoutOneWhateverTheName() {
        compose.setContent {
            NuageTheme {
                Column(Modifier.width(440.dp)) {
                    TitleNameSlot(name, treatment)
                    Text("2020 · 1 h 40 min · Animation", Modifier.testTag("metadata"))
                }
            }
        }
        val oneLine = metadataTop()
        name = "The Lord of the Rings: The Fellowship of the Ring"
        assertEquals(oneLine, metadataTop())
        treatment = cutout(800, 200)
        assertEquals(oneLine, metadataTop())
        treatment = cutout(200, 400)
        assertEquals(oneLine, metadataTop())
    }

    @Test fun theTreatmentStandsOnTheNamesBaselineInsideTheSlot() {
        treatment = cutout(800, 200)
        compose.setContent {
            NuageTheme {
                Column(Modifier.width(440.dp)) {
                    TitleNameSlot(name, treatment)
                    Text("2020", Modifier.testTag("metadata"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Soul").assertDoesNotExist()
        val drawn = compose.onNodeWithTag(TITLE_TREATMENT, useUnmergedTree = true).getBoundsInRoot()
        val metadata = compose.onNodeWithTag("metadata").getBoundsInRoot()
        // Its box is the slot above the name's descent: the column's width, and less than two Display lines tall.
        assertEquals(440f, drawn.right.value - drawn.left.value, 0.5f)
        assertTrue("treatment bottom ${drawn.bottom} over the metadata at ${metadata.top}", drawn.bottom < metadata.top)
        assertTrue(drawn.top.value >= 0f)
    }
}
