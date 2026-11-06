package dev.betteriptv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** VLC builds its font list once per install (41 s on the stick, #410): it is built ahead only when its cache is missing. */
class VlcFontsTest {
    @get:Rule val home = TemporaryFolder()

    @Test
    fun aFreshInstallHasNoFontList() {
        assertFalse(vlcFontsCached(home.root))
    }

    @Test
    fun anEmptyCacheFolderHasNoFontList() {
        home.newFolder(".cache", "fontconfig").resolve("CACHEDIR.TAG").writeText("")

        assertFalse(vlcFontsCached(home.root))
    }

    @Test
    fun aFontconfigCacheFileIsTheFontList() {
        home.newFolder(".cache", "fontconfig").resolve("3336a65c52528c9c368e942d3dd307f8-arm.cache-7").writeText("cache")

        assertTrue(vlcFontsCached(home.root))
    }
}
