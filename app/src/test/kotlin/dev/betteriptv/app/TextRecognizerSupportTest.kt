package dev.betteriptv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's recognizer stays off where it would crash the app: the Fire TV Stick's first poster read
 * died with SIGILL on `vsdot.s8` in libmlkit_google_ocr_pipeline.so (0.1.770, 2026-11-08), a
 * Cortex-A55 under a 32-bit kernel that doesn't enable the dot product.
 */
class TextRecognizerSupportTest {
    private fun cpuinfo(features: String, part: String, cores: Int = 4, implementer: String = "0x41") = (0 until cores).joinToString("\n\n") { n ->
        """
        processor	: $n
        Processor	: ARMv7 Processor rev 0 (v7l)
        model name	: ARMv7 Processor rev 0 (v7l)
        BogoMIPS	: 26.00
        Features	: $features
        CPU implementer	: $implementer
        CPU architecture: 7
        CPU variant	: 0x2
        CPU part	: $part
        CPU revision	: 0
        """.trimIndent()
    } + "\n\nHardware	: MT8696\nRevision	: 0000\n"

    private val stickFeatures = "half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm aes pmull sha1 sha2 crc32"

    @Test
    fun `the Fire TV Stick's Cortex-A55 under a 32-bit kernel doesn't run it`() {
        assertFalse(TextRecognizerSupport.runs("armeabi-v7a", cpuinfo(stickFeatures, "0xd05")))
    }

    @Test
    fun `a 32-bit kernel that reports the dot product runs it`() {
        assertTrue(TextRecognizerSupport.runs("armeabi-v7a", cpuinfo("$stickFeatures asimddp", "0xd05")))
    }

    @Test
    fun `a core without the dot product runs it, its kernels then don't use it`() {
        // Cortex-A53.
        assertTrue(TextRecognizerSupport.runs("armeabi-v7a", cpuinfo(stickFeatures, "0xd03")))
    }

    @Test
    fun `a 64-bit or x86 process runs it`() {
        assertTrue(TextRecognizerSupport.runs("arm64-v8a", cpuinfo(stickFeatures, "0xd05")))
        assertTrue(TextRecognizerSupport.runs("x86_64", ""))
    }
}
