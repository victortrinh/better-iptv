package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * A card refuses a poster whose file is under 300 px wide and takes the Title's next one (#526): a
 * 154 × 231 file blurs at card size. The file's width is read from its header ([PosterFloor]).
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class PosterFloorTest {
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

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun forget() {
        SingletonImageLoader.reset()
        PosterFloor.refused.clear()
    }

    /** A black [width] × [height] PNG file. */
    private fun png(width: Int, height: Int): ByteArray {
        val file = ByteArrayOutputStream()
        val out = DataOutputStream(file)
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val typed = type.toByteArray() + data
            out.write(typed)
            out.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
        chunk("IDAT", ByteArrayOutputStream().also { DeflaterOutputStream(it).use { rows -> rows.write(ByteArray(height * (1 + width * 3))) } }.toByteArray())
        chunk("IEND", ByteArray(0))
        return file.toByteArray()
    }

    private fun decoderFor(file: ByteArray, poster: Boolean) = PosterFloor().create(
        SourceFetchResult(ImageSource(Buffer().write(file), FileSystem.SYSTEM), "image/png", DataSource.NETWORK),
        Options(context).let { if (poster) it.copy(extras = it.extras.newBuilder().apply { set(PosterFloor.Key, true) }.build()) else it },
        ImageLoader(context),
    )

    @Test fun aPosterUnder300PxWideIsRefused() {
        val decoder = decoderFor(png(154, 231), poster = true)

        assertNotNull(decoder)
        val refused = runCatching { runBlocking { decoder!!.decode() } }.exceptionOrNull()
        assertTrue("refused with $refused", refused is PosterFloor.TooNarrow)
    }

    @Test fun aPoster300PxWideOrMoreIsLeftToCoil() {
        assertNull(decoderFor(png(300, 450), poster = true))
    }

    @Test fun otherArtworkIsNeverJudged() {
        assertNull(decoderFor(png(100, 100), poster = false))
    }

    @Test fun aCardWhosePosterIsRefusedTakesTheNextOne() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val serve = Interceptor { chain ->
            val url = chain.request.data.toString()
            asked += url
            if (url.endsWith("narrow.jpg")) ErrorResult(null, chain.request, PosterFloor.TooNarrow(154)) as ImageResult
            else SuccessResult(ColorDrawable(0xFFFF0000.toInt()).asImage(), chain.request, dataSource = DataSource.MEMORY_CACHE)
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())

        compose.setContent {
            val poster = rememberPoster(listOf("https://img.example/narrow.jpg", "https://img.example/wide.jpg"))
            Box(Modifier.size(136.dp, 204.dp).then(if (poster != null) Modifier.paint(poster) else Modifier))
        }
        compose.waitForIdle()

        assertEquals(listOf("https://img.example/narrow.jpg", "https://img.example/wide.jpg"), asked.distinct())
        assertTrue("https://img.example/narrow.jpg" in PosterFloor.refused)
    }
}
