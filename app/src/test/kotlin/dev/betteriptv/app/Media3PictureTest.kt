package dev.betteriptv.app

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.core.view.children
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Text subtitles are drawn over the whole screen, so they land in the black bar under a wide picture (#292). */
@RunWith(RobolectricTestRunner::class)
class Media3PictureTest {
    @Test
    fun textCuesGoOverTheScreenAndBitmapsOnThePicture() {
        val text = Cue.Builder().setText("A line").build()
        val bitmap = Cue.Builder().setBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)).build()
        assertEquals(listOf(bitmap) to listOf(text), Media3Picture.split(listOf(text, bitmap)))
    }

    @Test
    fun oneSubtitleViewFillsTheScreenAndOneThePicturesFrame() {
        val picture = Media3Picture(RuntimeEnvironment.getApplication())
        val frame = picture.view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
        assertTrue(frame.children.any { it is SubtitleView })
        assertTrue(picture.view.children.any { it is SubtitleView })
        // Media3's own subtitle view, which would sit in the frame, is left out of the layout.
        assertNull(picture.view.subtitleView)
    }
}
