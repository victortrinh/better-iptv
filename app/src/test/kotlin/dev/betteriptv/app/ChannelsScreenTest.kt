package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.BrowsedCategory
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
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
 * The Channels screen's focus (#259): the column of Categories and the list of the picked one's
 * Channels, Left and Right between them, OK watching and the menu key favouriting. Its data comes
 * from the Library ([ChannelBrowserTest] in core covers it); here it is made up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class ChannelsScreenTest {
    /** The compose rule's host activity, registered as [RowEntryTest] does so release unit tests run it too. */
    @get:Rule(order = 0)
    val host = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                shadowOf(context.packageManager).addOrUpdateActivity(
                    ActivityInfo().apply {
                        name = ComponentActivity::class.java.name
                        packageName = context.packageName
                    },
                )
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val sports = BrowsedCategory("SPORTS", listOf("1"), 2)
    private val general = BrowsedCategory("GENERAL", listOf("2"), 3)
    private val channels = mapOf(
        sports to listOf(channel("UK: TNT SPORT 1"), channel("UK: SKY SPORTS")),
        general to listOf(channel("UK: BBC ONE"), channel("UK: BBC TWO"), channel("UK: ITV")),
    )
    private val watched = ArrayList<String>()
    private val favourited = ArrayList<Pair<String, Boolean>>()

    private fun channel(key: String) = Channel(key, key.substringAfter(": "), "UK", null, favourite = false, listOf(ChannelVariant(1, key, null, false)))

    private fun screen(state: ChannelsState = ChannelsState()) = compose.setContent {
        NuageTheme {
            ChannelsLayout(
                "United Kingdom", 5, listOf(sports, general), state,
                channelsOf = { channels.getValue(it) },
                onWatch = { watched += it.key },
                onFavourite = { channel, favourite -> favourited += channel.key to favourite },
            )
        }
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun theFirstCategoryTakesFocusOnArrivalAndItsChannelsAreListed() {
        screen()

        compose.onNodeWithText("SPORTS").assertIsFocused()
        compose.onNodeWithText("TNT SPORT 1").assertExists()
        compose.onNodeWithText("BBC ONE").assertDoesNotExist()
    }

    @Test
    fun downPicksTheNextCategoryAndRightGoesIntoItsChannels() {
        screen()
        press(listOf(Key.DirectionDown))
        compose.onNodeWithText("GENERAL").assertIsFocused()
        compose.onNodeWithText("BBC ONE").assertExists()

        press(listOf(Key.DirectionRight))
        compose.onNodeWithText("BBC ONE").assertIsFocused()
    }

    @Test
    fun leftFromAnyChannelReturnsToThePickedCategory() {
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionDown, Key.DirectionDown))
        compose.onNodeWithText("ITV").assertIsFocused()

        press(listOf(Key.DirectionLeft))
        compose.onNodeWithText("GENERAL").assertIsFocused()
        // Right again returns to the Channel focused last.
        press(listOf(Key.DirectionRight))
        compose.onNodeWithText("ITV").assertIsFocused()
    }

    @Test
    fun okOnACategoryGoesIntoItsChannels() {
        screen()
        press(listOf(Key.DirectionCenter))

        compose.onNodeWithText("TNT SPORT 1").assertIsFocused()
    }

    @Test
    fun okWatchesTheChannelAndTheMenuKeyFavouritesIt() {
        screen()
        press(listOf(Key.DirectionRight, Key.DirectionDown, Key.Menu))
        assertEquals(listOf("UK: SKY SPORTS" to true), favourited)
        compose.onNodeWithText("≡ Remove from Favourites").assertExists()

        press(listOf(Key.DirectionCenter))
        assertEquals(listOf("UK: SKY SPORTS"), watched)
    }

    @Test
    fun backFromTheChannelReturnsToItsRow() {
        val state = ChannelsState().apply {
            category = "GENERAL"
            channel = "UK: BBC TWO"
        }
        screen(state)

        compose.onNodeWithText("BBC TWO").assertIsFocused()
    }
}
