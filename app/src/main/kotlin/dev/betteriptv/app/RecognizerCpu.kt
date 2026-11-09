package dev.betteriptv.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Keeps ML Kit's text recognizer from crashing the Fire TV Stick (ADR 0017). The recognizer's 32-bit
 * library picks its dot-product kernels (`vsdot`, ARMv8.2) from the CPU's model in /proc/cpuinfo, not
 * from what the kernel can run: the stick (MT8696, Cortex-A55 r2 under a 32-bit kernel) has the
 * instruction, but its kernel turns the FPU off at every context switch and only knows the classic
 * NEON encodings when the next FPU instruction traps, so a `vsdot` first in line dies with SIGILL,
 * which no catch can stop (0.1.770, 2026-11-08). [prepare] detects such a device and, before the
 * recognizer first runs, points its reads of /proc/cpuinfo at a copy that names every core a
 * Cortex-A53: an ARMv8 core without the dot product, so the recognizer settles for plain NEON, which
 * every ARMv7 device runs. The redirect is a patch of one import in the library's own GOT
 * (recognizer_cpu.c); the library itself is untouched.
 */
object RecognizerCpu {
    private const val TAG = "RecognizerCpu"
    private const val LIBRARY = "mlkit_google_ocr_pipeline"
    private const val CPUINFO = "/proc/cpuinfo"

    /**
     * Readies the recognizer for this device and says whether it may run. On a device where it would
     * trap ([trapsDotProduct]), writes the disguised /proc/cpuinfo ([disguise]) under the app's files,
     * loads the recognizer's library and redirects its reads to the copy. False when the redirect
     * couldn't be put in place: the recognizer then stays off and every Title is named in type.
     */
    fun prepare(context: Context): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val cpuinfo = runCatching { File(CPUINFO).readText() }.getOrDefault("")
        if (!trapsDotProduct(abi, cpuinfo)) return true
        return runCatching {
            val copy = File(context.filesDir, "cpuinfo")
            copy.writeText(disguise(cpuinfo))
            System.loadLibrary(LIBRARY)
            System.loadLibrary("recognizer_cpu")
            val patched = redirect("lib$LIBRARY.so", copy.absolutePath)
            Log.i(TAG, "the recognizer's dot product would trap here: $patched import(s) redirected to ${copy.path}")
            patched > 0
        }.onFailure { Log.w(TAG, "couldn't redirect the recognizer's /proc/cpuinfo; Titles are named in type", it) }.getOrDefault(false)
    }

    /**
     * Whether the recognizer, left to itself, traps in a process of [primaryAbi] on the CPU [cpuinfo]
     * describes: a 32-bit ARM process on an ARMv8.2 core (the recognizer then takes the dot product for
     * granted) whose kernel doesn't report it (`asimddp`).
     */
    fun trapsDotProduct(primaryAbi: String, cpuinfo: String): Boolean {
        if (!primaryAbi.startsWith("armeabi")) return false
        val lines = cpuinfo.lines()
        val features = lines.filter { it.startsWith("Features") }.flatMap { it.substringAfter(':').trim().split(' ') }
        if ("asimddp" in features) return false
        val implementers = lines.filter { it.startsWith("CPU implementer") }.map { it.substringAfter(':').trim().lowercase() }
        val parts = lines.filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim().lowercase() }
        return implementers.zip(parts).any { (implementer, part) -> "$implementer:$part" in DOT_PRODUCT_CORES }
    }

    /**
     * [cpuinfo] with every core named a Cortex-A53 (Arm part 0xd03, variant 0x0): an ARMv8 core
     * without the dot product, the FP16 arithmetic or the rounding-doubling multiplies, so the
     * recognizer's model-based guesses all come out false. Everything else, the features and the
     * Hardware line among them, stays as the kernel wrote it.
     */
    fun disguise(cpuinfo: String): String = cpuinfo.lines().joinToString("\n") { line ->
        when {
            line.startsWith("CPU implementer") -> line.substringBefore(':') + ": 0x41"
            line.startsWith("CPU part") -> line.substringBefore(':') + ": 0xd03"
            line.startsWith("CPU variant") -> line.substringBefore(':') + ": 0x0"
            else -> line
        }
    }

    /** Cores with the ARMv8.2 dot product, as `implementer:part` in /proc/cpuinfo's spelling. */
    private val DOT_PRODUCT_CORES = setOf(
        // Arm: Cortex-A55, A65, A75, A76, A76AE, A77, A78, X1, A78C, Neoverse N1, A510, A710, X2, A715, X3.
        "0x41:0xd05", "0x41:0xd06", "0x41:0xd0a", "0x41:0xd0b", "0x41:0xd0e", "0x41:0xd0d", "0x41:0xd41", "0x41:0xd44",
        "0x41:0xd4b", "0x41:0xd0c", "0x41:0xd46", "0x41:0xd47", "0x41:0xd48", "0x41:0xd4d", "0x41:0xd4e",
        // HiSilicon's Cortex-A76, Qualcomm's Kryo 4xx/5xx, Samsung's Exynos M4 and M5.
        "0x48:0xd40", "0x51:0x804", "0x51:0x805", "0x53:0x003", "0x53:0x004",
    )

    /**
     * Makes the loaded [library] (a file name, "libx.so") open [to] whenever it opens /proc/cpuinfo,
     * and returns how many of its imports were patched; 0 means no redirect is in place.
     */
    private external fun redirect(library: String, to: String): Int
}
