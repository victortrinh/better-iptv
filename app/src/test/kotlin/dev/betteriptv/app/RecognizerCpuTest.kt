package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's recognizer is kept from the dot product where it would crash the app (ADR 0017): the Fire
 * TV Stick's first poster read died with SIGILL on `vsdot.s8` in libmlkit_google_ocr_pipeline.so
 * (0.1.770, 2026-11-08), a Cortex-A55 r2 under a 32-bit kernel that only knows the classic NEON encodings.
 */
class RecognizerCpuTest {
    private fun cpuinfo(features: String, part: String, cores: Int = 4, implementer: String = "0x41", variant: String = "0x2") = (0 until cores).joinToString("\n\n") { n ->
        """
        processor	: $n
        Processor	: ARMv7 Processor rev 0 (v7l)
        model name	: ARMv7 Processor rev 0 (v7l)
        BogoMIPS	: 26.00
        Features	: $features
        CPU implementer	: $implementer
        CPU architecture: 7
        CPU variant	: $variant
        CPU part	: $part
        CPU revision	: 0
        """.trimIndent()
    } + "\n\nHardware	: MT8696\nRevision	: 0000\n"

    /** The stick's own /proc/cpuinfo (AFTKA, Fire OS 7 on kernel 4.14, 2026-11-08). */
    private val stickFeatures = "half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm aes pmull sha1 sha2 crc32"
    private val stick = cpuinfo(stickFeatures, "0xd05")

    @Test
    fun `the Fire TV Stick's Cortex-A55 under a 32-bit kernel traps`() {
        assertTrue(RecognizerCpu.trapsDotProduct("armeabi-v7a", stick))
    }

    @Test
    fun `a 32-bit kernel that reports the dot product doesn't trap`() {
        assertFalse(RecognizerCpu.trapsDotProduct("armeabi-v7a", cpuinfo("$stickFeatures asimddp", "0xd05")))
    }

    @Test
    fun `a core without the dot product doesn't trap, the recognizer never picks it there`() {
        // Cortex-A53.
        assertFalse(RecognizerCpu.trapsDotProduct("armeabi-v7a", cpuinfo(stickFeatures, "0xd03")))
    }

    @Test
    fun `a 64-bit or x86 process doesn't trap`() {
        assertFalse(RecognizerCpu.trapsDotProduct("arm64-v8a", stick))
        assertFalse(RecognizerCpu.trapsDotProduct("x86_64", ""))
    }

    @Test
    fun `the disguise names every core a Cortex-A53 r0 and changes nothing else`() {
        val disguised = RecognizerCpu.disguise(stick)
        assertEquals(cpuinfo(stickFeatures, "0xd03", variant = "0x0"), disguised)
        assertFalse(RecognizerCpu.trapsDotProduct("armeabi-v7a", disguised))
    }

    @Test
    fun `the disguise keeps the kernel's spelling, spacing and trailing newline`() {
        val odd = "CPU implementer\t: 0x51\nCPU part : 0x805\nCPU variant\t: 0xd\nHardware\t: Qualcomm\n"
        assertEquals("CPU implementer\t: 0x41\nCPU part : 0xd03\nCPU variant\t: 0x0\nHardware\t: Qualcomm\n", RecognizerCpu.disguise(odd))
    }
}
