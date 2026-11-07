package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.betteriptv.core.SignInCode
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

/** The Sign-in screen's panel (#429), with a scripted sign-in state: the code, the QR, the address and the waiting line. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class SignInPanelTest {
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
    val compose = createComposeRule()

    private val code = SignInCode("K7M2QX", "https://zugig.app/sign-in?code=K7M2QX", Duration.ofMinutes(10))

    @Test fun `shows the code, its QR and the page's address while it waits for the phone`() {
        var state by mutableStateOf<SignInState>(SignInState.Asking)
        compose.setContent { SignInPanel(state) }
        compose.onNodeWithTag("sign-in-code").assertTextEquals("")
        compose.onNodeWithText("Waiting for your phone…").assertIsDisplayed()

        state = SignInState.Showing(code)
        compose.onNodeWithTag("sign-in-code").assertTextEquals("K7M 2QX")
        compose.onNodeWithContentDescription("QR code for the sign-in page").assertIsDisplayed()
        compose.onNodeWithText("go to zugig.app/sign-in.", substring = true).assertIsDisplayed()

        state = SignInState.SignedIn("home@example.com")
        compose.onNodeWithText("Signed in as home@example.com").assertIsDisplayed()
    }

    @Test fun `says so in the error colour's words when the cloud can't give a code`() {
        compose.setContent { SignInPanel(SignInState.Unavailable) }
        compose.onNodeWithText("Can't reach Zugig right now. Trying again…").assertIsDisplayed()
    }

    @Test fun `the QR reads back as the sign-in page's address`() {
        val matrix = qrMatrix(code.url)
        // A scanner's view: each module a few pixels, dark on light, with a quiet zone.
        val scale = 4
        val quiet = 4
        val side = (matrix.width + 2 * quiet) * scale
        val pixels = ByteArray(side * side) { 0xFF.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * side, (y + 1) * side)
            override fun getMatrix(): ByteArray = pixels
        }
        assertEquals(code.url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }

    @Test fun `names the page as a Viewer would type it`() {
        assertEquals("zugig.app/sign-in", typedAddress("https://zugig.app/sign-in?code=K7M2QX"))
    }
}
