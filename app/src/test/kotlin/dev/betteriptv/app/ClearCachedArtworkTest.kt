package dev.betteriptv.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one-time clear of #480 empties the image loader's caches through its seam, so an artwork an
 * older app cached is loaded again from its link. How often it runs is the Library's
 * (`WithoutCloudMetadataTest`): once, on the first launch after the release.
 */
@RunWith(RobolectricTestRunner::class)
class ClearCachedArtworkTest {
    @get:Rule val folder = TemporaryFolder()

    /** A disk cache that counts its clears. */
    private class Counting(private val cache: DiskCache) : DiskCache by cache {
        var clears = 0
        override fun clear() {
            clears++
            cache.clear()
        }
    }

    @Test fun `clearing empties the disk cache and the memory cache, once per call`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disk = Counting(DiskCache.Builder().directory(folder.root.toOkioPath()).fileSystem(FileSystem.SYSTEM).maxSizeBytes(1_000_000).build())
        val loader = ImageLoader.Builder(context).diskCache { disk }.memoryCache { MemoryCache.Builder().maxSizeBytes(1_000_000).build() }.build()
        disk.openEditor("https://img.example/poster.jpg")!!.apply {
            FileSystem.SYSTEM.write(data) { writeUtf8("poster") }
            commit()
        }
        assertNotNull(disk.openSnapshot("https://img.example/poster.jpg")?.also { it.close() })

        clearCachedArtwork(loader)

        assertEquals(1, disk.clears)
        assertNull(disk.openSnapshot("https://img.example/poster.jpg"))
        assertEquals(0L, loader.memoryCache!!.size)
    }
}
