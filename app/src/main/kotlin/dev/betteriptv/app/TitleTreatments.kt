package dev.betteriptv.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.Painter
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.Pixels
import dev.betteriptv.core.TextLine
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TreatmentCut
import dev.betteriptv.core.cutTreatment
import dev.betteriptv.core.treatmentCrop
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Title treatments (ADR 0016): the studio's lettering cut out of a Title's poster on the device,
 * shown in place of its name on the hero and the Title page. A Title's is cut once, when its hero or
 * page first needs it ([cut]), off the main thread and one at a time: the poster is read from the
 * image cache, ML Kit's bundled Latin text recognizer finds its lines (Fire OS has no Google Play
 * services, so the bundled model, not the Play-services one), and [cutTreatment] crops, keys and
 * checks it. The poster is the one the Title's card shows: the first of its posters the floor doesn't
 * refuse ([PosterFloor], #526), else the next ones in the card's order when that one gives none. The accepted cut-out is kept as a PNG with alpha beside the image
 * cache, keyed by the Title and its posters' URLs; a rejection is kept too, so the recognizer never
 * runs twice on the same posters.
 */
class TitleTreatments(context: Context) {
    private val context = context.applicationContext
    private val dir = File(context.cacheDir, "treatments")
    private val prefs = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
    private val known = ConcurrentHashMap<String, Known>()
    private val cutting = ConcurrentHashMap<String, Deferred<Known?>>()
    private val indexed = CompletableDeferred<Unit>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val _typedOnly = MutableStateFlow(prefs.getBoolean(TYPED_ONLY, false))

    /** Settings' debug switch: every Title named in Display type, for comparing. */
    val typedOnly: StateFlow<Boolean> = _typedOnly.asStateFlow()

    fun setTypedOnly(on: Boolean) {
        _typedOnly.value = on
        prefs.edit().putBoolean(TYPED_ONLY, on).apply()
    }

    init {
        // What earlier launches cut, so a Title's treatment is known on its first frame.
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.forEach { file ->
                when (file.extension) {
                    "png" -> known[file.nameWithoutExtension] = Known.Cut(file.toURI().toString())
                    "none" -> known[file.nameWithoutExtension] = Known.Typed
                }
            }
            indexed.complete(Unit)
        }
    }

    /** What is known of the treatment of Title [id] with [posters]: its cut-out, that it is named in type, or null when it hasn't been cut yet. */
    fun known(id: TitleId, posters: List<String>): Known? = known[key(id, posters)]

    /**
     * Cuts the treatment of Title [id], named [name], from the poster its card shows of [posters],
     * unless it is known or being cut: on the app's own scope, so leaving the screen doesn't waste
     * the work. Null when no poster could be loaded: nothing is remembered then, and the next visit
     * tries again.
     */
    fun cut(id: TitleId, name: String, posters: List<String>): Deferred<Known?> {
        val key = key(id, posters)
        known[key]?.let { return CompletableDeferred(it) }
        return cutting.computeIfAbsent(key) {
            scope.async {
                try {
                    indexed.await()
                    known[key] ?: cutNow(key, name, posters)?.also { known[key] = it }
                } finally {
                    cutting.remove(key)
                }
            }
        }
    }

    /** [cut] for the featured [title]'s poster; null when it has none. */
    fun cutFor(title: FeaturedTitle): Deferred<Known?>? = title.posters.takeIf { it.isNotEmpty() }?.let { cut(title.id, title.name, it) }

    /**
     * Cuts the treatment from the poster the card shows of [posters], else from the next ones in the
     * card's order, [POSTERS_TRIED] at most: lettering OCR can't read on one poster (a logo drawn as
     * shapes) is often plain on another Listing's. The name check still decides each, so a poster
     * lettered in another name isn't taken. Null when no poster could be loaded.
     */
    private suspend fun cutNow(key: String, name: String, posters: List<String>): Known? {
        var tried = 0
        var refused: TreatmentCut.Refused? = null
        for (url in posters) {
            if (tried == POSTERS_TRIED) break
            if (url in PosterFloor.refused) continue
            val started = SystemClock.elapsedRealtime()
            val result = posterBitmap(url)
            if (result is ErrorResult && result.throwable is PosterFloor.TooNarrow) {
                PosterFloor.refused.add(url)
                continue
            }
            val bitmap = result.image?.toBitmap() ?: continue
            tried++
            val loaded = SystemClock.elapsedRealtime()
            val lines = try {
                recognise(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "text recognition failed for $name", e)
                return null
            }
            val recognised = SystemClock.elapsedRealtime()
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "$name, poster $tried lines: " + lines.joinToString { "\"${it.text}\" @ %.3f,%.3f %.3f×%.3f".format(it.x, it.y, it.width, it.height) })
            }
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            val cut = cutTreatment(name, Pixels(bitmap.width, bitmap.height, pixels), lines)
            Log.i(TAG, "$name, poster $tried: ${(cut as? TreatmentCut.Refused)?.verdict ?: "Accepted"}, read \"${treatmentCrop(lines, name)?.text.orEmpty()}\" (${bitmap.width}×${bitmap.height}, " +
                "load ${loaded - started} ms, recognise ${recognised - loaded} ms, cut ${SystemClock.elapsedRealtime() - recognised} ms)")
            when (cut) {
                is TreatmentCut.Accepted -> return keep(key, cut)
                is TreatmentCut.Refused -> if (refused == null) refused = cut
            }
        }
        val first = refused ?: return null
        dir.mkdirs()
        File(dir, "$key.none").writeText(first.verdict.name)
        return Known.Typed
    }

    /** Keeps the accepted [cut] as a PNG with alpha, under [key]. */
    private fun keep(key: String, cut: TreatmentCut.Accepted): Known {
        dir.mkdirs()
        val file = File(dir, "$key.png")
        val temp = File(dir, "$key.tmp")
        val out = Bitmap.createBitmap(cut.cutout.argb, cut.cutout.width, cut.cutout.height, Bitmap.Config.ARGB_8888)
        temp.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        temp.renameTo(file)
        return Known.Cut(file.toURI().toString())
    }

    /** The poster at [url], from the image cache when it's there, as a software bitmap no larger than [POSTER_SIDE], refused when narrow ([PosterFloor]). */
    private suspend fun posterBitmap(url: String): ImageResult {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(POSTER_SIDE)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            // Not kept in memory at this size: the cards and the page load it at theirs.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .apply { extras[PosterFloor.Key] = true }
            .build()
        return SingletonImageLoader.get(context).execute(request)
    }

    /** The lines ML Kit recognises on [bitmap], in fractions of its size. */
    private suspend fun recognise(bitmap: Bitmap): List<TextLine> {
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TextLine(line.text, box.left / w, box.top / h, box.width() / w, box.height() / h)
        }
    }

    /** What is known of a treatment: [Cut] at a file [uri], or [Typed], the Title named in Display type. */
    sealed interface Known {
        data class Cut(val uri: String) : Known
        data object Typed : Known
    }

    private companion object {
        const val TAG = "TitleTreatment"
        const val TYPED_ONLY = "typed_names_only"
        /** Posters are read at most this tall or wide: a bigger one adds nothing to a slot two Display lines tall. */
        const val POSTER_SIDE = 1600
        /** At most this many of a Title's posters are read before it is named in type. */
        const val POSTERS_TRIED = 4

        /** The rule's version ([cutTreatment]): a release that changes it cuts every poster again. */
        const val RULE = 3

        fun key(id: TitleId, posters: List<String>): String =
            MessageDigest.getInstance("SHA-1").digest("$RULE\n$id\n${posters.joinToString("\n")}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Whether ML Kit's bundled text recognizer can run on this device, read once as the app starts. Its
 * 32-bit ARM library picks its dot-product kernels (`vsdot`, ARMv8.2) from the CPU's model, not from
 * what the kernel lets it run: the Fire TV Stick (MT8696, Cortex-A55 under 32-bit Fire OS 9) has the
 * instruction but its kernel doesn't enable it (no `asimddp` in its features), so the first poster
 * read killed the app with SIGILL, which no catch can stop. There, every Title keeps its typed name.
 */
object TextRecognizerSupport {
    val runs: Boolean by lazy {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        runs(abi, cpuinfo)
    }

    /**
     * Whether the recognizer runs in a process of [primaryAbi] on the CPU [cpuinfo] describes: not in a
     * 32-bit ARM process on an ARMv8.2 core (the recognizer then takes the dot product for granted) whose
     * kernel doesn't report it (`asimddp`).
     */
    fun runs(primaryAbi: String, cpuinfo: String): Boolean {
        if (!primaryAbi.startsWith("armeabi")) return true
        val lines = cpuinfo.lines()
        val features = lines.filter { it.startsWith("Features") }.flatMap { it.substringAfter(':').trim().split(' ') }
        if ("asimddp" in features) return true
        val implementers = lines.filter { it.startsWith("CPU implementer") }.map { it.substringAfter(':').trim().lowercase() }
        val parts = lines.filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim().lowercase() }
        return implementers.zip(parts).none { (implementer, part) -> "$implementer:$part" in DOT_PRODUCT_CORES }
    }

    /** Cores with the ARMv8.2 dot product, as `implementer:part` in /proc/cpuinfo's spelling. */
    private val DOT_PRODUCT_CORES = setOf(
        // Arm: Cortex-A55, A65, A75, A76, A76AE, A77, A78, X1, A78C, Neoverse N1, A510, A710, X2, A715, X3.
        "0x41:0xd05", "0x41:0xd06", "0x41:0xd0a", "0x41:0xd0b", "0x41:0xd0e", "0x41:0xd0d", "0x41:0xd41", "0x41:0xd44",
        "0x41:0xd4b", "0x41:0xd0c", "0x41:0xd46", "0x41:0xd47", "0x41:0xd48", "0x41:0xd4d", "0x41:0xd4e",
        // HiSilicon's Cortex-A76, Qualcomm's Kryo 4xx/5xx, Samsung's Exynos M4 and M5.
        "0x48:0xd40", "0x51:0x804", "0x51:0x805", "0x53:0x003", "0x53:0x004",
    )
}

/** The app's [TitleTreatments]; none in tests, where every Title is named in type. */
val LocalTitleTreatments = staticCompositionLocalOf<TitleTreatments?> { null }

/**
 * The treatment of Title [id] named [name], cut from the one of its [posters] its card shows, to draw
 * in place of its name; null while it isn't known, when it was refused or there's no poster, or while Settings' debug switch
 * names every Title in type. It is decided once per visit: a Title whose treatment isn't known yet as
 * the screen opens is cut in the background and keeps its typed name until the next visit, so the
 * lettering never pops in over the name.
 */
@Composable
fun rememberTitleTreatment(id: TitleId, name: String, posters: List<String>): Painter? {
    val treatments = LocalTitleTreatments.current ?: return null
    val typedOnly by treatments.typedOnly.collectAsState()
    val known = remember(id, posters) { posters.takeIf { it.isNotEmpty() }?.let { treatments.known(id, it) } }
    LaunchedEffect(id, posters) {
        if (posters.isNotEmpty() && known == null) treatments.cut(id, name, posters)
    }
    val painter = rememberArtwork((known as? TitleTreatments.Known.Cut)?.uri)
    return painter.takeUnless { typedOnly }
}
