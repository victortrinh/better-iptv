package dev.betteriptv.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The Title treatment (CONTEXT.md, ADR 0016): the studio's lettering cut out of the Provider's poster,
 * shown in place of the name in Display type on the hero and the Title page. A text recognizer finds
 * the poster's lines ([TextLine]); [treatmentCrop] takes the block of the title; [colourKey] keys the
 * crop against its own border colour; [treatmentVerdict] decides whether it is shown; [despeckle]
 * drops the specks around it. The Mac copy of the rule is scripts/artwork/cutout.swift and
 * scripts/survey_treatment.py (Survey C); the device adds to it the name-led choice of the block, the
 * wider side padding and the despeckling.
 */

/**
 * A line of text the recognizer found on a poster, its box as fractions of the poster's width and
 * height, from the top left.
 */
data class TextLine(val text: String, val x: Double, val y: Double, val width: Double, val height: Double)

/** The block of text [treatmentCrop] keeps: its box in fractions of the poster, and its lines' text, top to bottom, joined by " | ". */
data class TreatmentCrop(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val text: String) {
    /** The box in pixels on a poster of [width] × [height], widened to whole pixels: left, top, right, bottom (exclusive). */
    fun pixels(width: Int, height: Int): IntArray = intArrayOf(
        floor(x0 * width).toInt().coerceIn(0, width),
        floor(y0 * height).toInt().coerceIn(0, height),
        ceil(x1 * width).toInt().coerceIn(0, width),
        ceil(y1 * height).toInt().coerceIn(0, height),
    )
}

/** Lines no wider than this fraction of the poster are left out: a credit, a rating, a stray letter. */
private const val MIN_LINE_WIDTH = 0.08
/** A line at least this fraction as tall as the tallest is part of its block. */
private const val BLOCK_HEIGHT = 0.45
/** ...when it starts within this fraction of the poster's height of it. */
private const val BLOCK_REACH = 0.14
/** A line at least this fraction as tall sitting right above or below the block joins it ("THE"). */
private const val SMALL_LINE_HEIGHT = 0.25
/** "Right above or below": its edge within this many times the tallest line's height of the block's. */
private const val SMALL_LINE_GAP = 0.6
/**
 * The crop's padding around the block, fractions of the poster: wider at the sides, where the
 * recognizer's boxes sit inside a round first letter ("O", "D").
 */
private const val CROP_PAD_X = 0.03
private const val CROP_PAD_Y = 0.012
/** A block whose tallest line is shorter than this fraction of the poster can't be the title: credits that name it don't count. */
private const val MIN_TITLE_HEIGHT = 0.03

/**
 * The block of the poster's title text, from the recognizer's [lines]: a block is a line wider than 8%
 * of the poster, the lines at least 45% as tall within 0.14 of it vertically, and a line at least a
 * quarter as tall sitting right above or below them (for "THE"), padded 3% sideways and 1.2% up and
 * down. The block kept is the tallest one that reads as the Title's [name] ([treatmentNameMatches])
 * and is at least 3% of the poster tall, so a studio's logo or a tagline set bigger than the title
 * doesn't take its place; when none reads as the name (or there is no name), the tallest. Null when
 * no line is wide enough.
 */
fun treatmentCrop(lines: List<TextLine>, name: String = ""): TreatmentCrop? {
    val wide = lines.filter { it.width > MIN_LINE_WIDTH }
    val tallestFirst = wide.sortedByDescending { it.height }
    val tallest = tallestFirst.firstOrNull() ?: return null
    if (name.isEmpty()) return block(wide, tallest)
    return tallestFirst.asSequence()
        .takeWhile { it.height >= MIN_TITLE_HEIGHT }
        .map { block(wide, it) }
        .firstOrNull { treatmentNameMatches(name, it.text) }
        ?: block(wide, tallest)
}

/** The block of the [wide] lines around the line [tall], padded. */
private fun block(wide: List<TextLine>, tall: TextLine): TreatmentCrop {
    val selected = wide.filter { it.height >= BLOCK_HEIGHT * tall.height && abs(it.y - tall.y) < BLOCK_REACH }.toMutableList()
    val top = selected.minOf { it.y }
    val bottom = selected.maxOf { it.y + it.height }
    for (line in wide) {
        if (selected.any { it.text == line.text && it.y == line.y }) continue
        val besideBlock = abs(line.y + line.height - top) < SMALL_LINE_GAP * tall.height || abs(line.y - bottom) < SMALL_LINE_GAP * tall.height
        if (line.height >= SMALL_LINE_HEIGHT * tall.height && besideBlock) selected += line
    }
    return TreatmentCrop(
        x0 = max(0.0, selected.minOf { it.x } - CROP_PAD_X),
        y0 = max(0.0, selected.minOf { it.y } - CROP_PAD_Y),
        x1 = min(1.0, selected.maxOf { it.x + it.width } + CROP_PAD_X),
        y1 = min(1.0, selected.maxOf { it.y + it.height } + CROP_PAD_Y),
        text = selected.sortedBy { it.y }.joinToString(" | ") { it.text },
    )
}

/** An image's pixels, row by row, as ARGB colour ints (not premultiplied), as Android's Bitmap.getPixels gives them. */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "$width × $height pixels, ${argb.size} given" }
    }

    /** The pixels of the box from [left], [top] to [right], [bottom] (exclusive). */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): Pixels {
        val w = right - left
        val h = bottom - top
        val out = IntArray(w * h)
        for (row in 0 until h) System.arraycopy(argb, (top + row) * width + left, out, row * w, w)
        return Pixels(w, h, out)
    }
}

/**
 * A crop colour-keyed against its border ([colourKey]): the [cutout] (each kept pixel its own colour,
 * at the alpha the key gave it), the [threshold] the key used, and what the checks read: the
 * share of the crop kept ([coverage]) and the kept pixels' mean [luma] and [chroma], 0 to 1.
 */
class Keyed(val cutout: Pixels, val threshold: Int, val coverage: Double, val luma: Double, val chroma: Double)

/** The share of the crop's shorter side its border ring takes, the ring whose median colour is the background. */
private const val RING = 0.06
/** RGB distances fall in 0..441 (the cube's diagonal), one bin each. */
private const val DISTANCE_BINS = 442
/** The key goes from transparent to opaque over this many units either side of the threshold. */
private const val SOFTEN = 18.0

/**
 * The [crop] colour-keyed against its own background: the median colour of its outer 6% is the
 * background; each pixel's RGB distance to it is thresholded by Otsu's method over a 442-bin
 * histogram and softened over ±18 units into alpha; the kept pixels keep their colour.
 */
fun colourKey(crop: Pixels): Keyed {
    val w = crop.width
    val h = crop.height
    val n = w * h
    val ring = max(2, (min(w, h) * RING).toInt())
    val reds = ArrayList<Int>()
    val greens = ArrayList<Int>()
    val blues = ArrayList<Int>()
    for (y in 0 until h) for (x in 0 until w) {
        if (x < ring || x >= w - ring || y < ring || y >= h - ring) {
            val c = crop.argb[y * w + x]
            reds += (c shr 16) and 0xFF
            greens += (c shr 8) and 0xFF
            blues += c and 0xFF
        }
    }
    reds.sort(); greens.sort(); blues.sort()
    val bgR = reds[reds.size / 2].toDouble()
    val bgG = greens[greens.size / 2].toDouble()
    val bgB = blues[blues.size / 2].toDouble()

    val distance = DoubleArray(n)
    val histogram = IntArray(DISTANCE_BINS)
    for (i in 0 until n) {
        val c = crop.argb[i]
        val r = ((c shr 16) and 0xFF) - bgR
        val g = ((c shr 8) and 0xFF) - bgG
        val b = (c and 0xFF) - bgB
        val d = sqrt(r * r + g * g + b * b)
        distance[i] = d
        histogram[min(DISTANCE_BINS - 1, d.toInt())]++
    }
    val threshold = otsu(histogram, n)

    val out = IntArray(n)
    var kept = 0.0
    var luma = 0.0
    var chroma = 0.0
    for (i in 0 until n) {
        val alpha = ((distance[i] - (threshold - SOFTEN)) / (2 * SOFTEN)).coerceIn(0.0, 1.0)
        val c = crop.argb[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        out[i] = ((alpha * 255).toInt() shl 24) or (c and 0xFFFFFF)
        kept += alpha
        luma += alpha * (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        chroma += alpha * (max(r, max(g, b)) - min(r, min(g, b))) / 255.0
    }
    return Keyed(
        Pixels(w, h, out),
        threshold,
        coverage = kept / n,
        luma = if (kept > 0) luma / kept else 0.0,
        chroma = if (kept > 0) chroma / kept else 0.0,
    )
}

/** Otsu's threshold over [histogram] of [total] values: the bin that best splits them into two classes. */
private fun otsu(histogram: IntArray, total: Int): Int {
    var sum = 0.0
    for (i in histogram.indices) sum += i.toDouble() * histogram[i]
    var sumBelow = 0.0
    var weightBelow = 0.0
    var best = 0.0
    var threshold = 0
    for (i in histogram.indices) {
        weightBelow += histogram[i]
        if (weightBelow == 0.0) continue
        val weightAbove = total - weightBelow
        if (weightAbove == 0.0) break
        sumBelow += i.toDouble() * histogram[i]
        val meanBelow = sumBelow / weightBelow
        val meanAbove = (sum - sumBelow) / weightAbove
        val between = weightBelow * weightAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
        if (between > best) {
            best = between
            threshold = i
        }
    }
    return threshold
}

/** Whether a cut-out is shown, or why not; anything but [Accepted] names the Title in Display type. */
enum class TreatmentVerdict {
    Accepted,
    /** The recognizer found no line wide enough. */
    NoText,
    /** The recognised text holds under half the name's words: a badge, a tagline, credits. */
    NotTheName,
    /** The kept pixels are dark and dull: the lettering would vanish on the hero's ground. */
    DarkTitle,
    /** The key kept almost nothing, or almost the whole crop. */
    KeyFailed,
}

/** The kept pixels must average at least this in luma or in chroma: a saturated red title is dark in luma but shows. */
private const val VISIBILITY_FLOOR = 0.30
/** The share of the crop the key must keep. */
private const val MIN_COVERAGE = 0.03
private const val MAX_COVERAGE = 0.70

/**
 * Whether the cut-out of the Title named [name] is shown, from the recognised [text] of its crop and
 * what the key kept ([Keyed]): the text holds at least half the name's words ([treatmentNameMatches]),
 * the kept pixels average at least 0.30 in [luma] or in [chroma], and the key kept 3–70% of the crop
 * ([coverage]). An empty [text] is a poster with no text found.
 */
fun treatmentVerdict(name: String, text: String, luma: Double, chroma: Double, coverage: Double): TreatmentVerdict = when {
    text.isEmpty() -> TreatmentVerdict.NoText
    !treatmentNameMatches(name, text) -> TreatmentVerdict.NotTheName
    max(luma, chroma) < VISIBILITY_FLOOR -> TreatmentVerdict.DarkTitle
    coverage !in MIN_COVERAGE..MAX_COVERAGE -> TreatmentVerdict.KeyFailed
    else -> TreatmentVerdict.Accepted
}

/** What cutting a poster's treatment gave: the cut-out to show, or why the Title is named in type. */
sealed interface TreatmentCut {
    /** The lettering, transparent around it, as [colourKey] cut it. */
    class Accepted(val cutout: Pixels) : TreatmentCut

    /** No cut-out passed: the [verdict] says why. */
    data class Refused(val verdict: TreatmentVerdict) : TreatmentCut
}

/**
 * The treatment of the Title named [name], cut from its [poster] and the [lines] a text recognizer
 * found on it: the block of the title text ([treatmentCrop]), colour-keyed ([colourKey]), shown once
 * it passes the checks ([treatmentVerdict]), its specks dropped ([despeckle]).
 */
fun cutTreatment(name: String, poster: Pixels, lines: List<TextLine>): TreatmentCut {
    val block = treatmentCrop(lines, name) ?: return TreatmentCut.Refused(TreatmentVerdict.NoText)
    val (left, top, right, bottom) = block.pixels(poster.width, poster.height).toList()
    if (right - left < 1 || bottom - top < 1) return TreatmentCut.Refused(TreatmentVerdict.NoText)
    val keyed = colourKey(poster.crop(left, top, right, bottom))
    val verdict = treatmentVerdict(name, block.text, keyed.luma, keyed.chroma, keyed.coverage)
    if (verdict != TreatmentVerdict.Accepted) return TreatmentCut.Refused(verdict)
    return TreatmentCut.Accepted(despeckle(keyed.cutout, poster.height))
}

/** An island whose alpha adds up to less than this share of the poster's height, squared, is a speck. */
private const val SPECK = 0.005

/**
 * The [cutout] without its specks: the islands of kept pixels (4-connected, any alpha) whose alpha
 * adds up to less than (0.5% of [posterHeight])² pixels, the embers and stars around a title that the
 * key keeps because they're nothing like the background. On a 900 px poster that is 20 pixels: the
 * specks around "OPPENHEIMER" weigh under 4, the thinnest letter of "THE FELLOWSHIP OF THE RING"
 * under "THE LORD OF THE RINGS" 50, the macron of "SHŌGUN" 131.
 */
fun despeckle(cutout: Pixels, posterHeight: Int): Pixels {
    val w = cutout.width
    val h = cutout.height
    val n = w * h
    val least = SPECK * posterHeight * SPECK * posterHeight
    val out = cutout.argb.copyOf()
    val seen = BooleanArray(n)
    val island = IntArray(n)
    val stack = IntArray(n)
    for (start in 0 until n) {
        if (seen[start] || out[start] ushr 24 == 0) continue
        var size = 0
        var mass = 0.0
        var depth = 0
        stack[depth++] = start
        seen[start] = true
        while (depth > 0) {
            val i = stack[--depth]
            island[size++] = i
            mass += (out[i] ushr 24) / 255.0
            val x = i % w
            if (x > 0) depth = visit(i - 1, out, seen, stack, depth)
            if (x < w - 1) depth = visit(i + 1, out, seen, stack, depth)
            if (i >= w) depth = visit(i - w, out, seen, stack, depth)
            if (i < n - w) depth = visit(i + w, out, seen, stack, depth)
        }
        if (mass < least) for (k in 0 until size) out[island[k]] = out[island[k]] and 0xFFFFFF
    }
    return Pixels(w, h, out)
}

/** Pushes pixel [i] on the [stack] (at [depth]) when it is kept and not seen yet; the new depth. */
private fun visit(i: Int, argb: IntArray, seen: BooleanArray, stack: IntArray, depth: Int): Int {
    if (seen[i] || argb[i] ushr 24 == 0) return depth
    seen[i] = true
    stack[depth] = i
    return depth + 1
}

/** Articles and joining words left out of the name's words, in the catalog's languages. */
private val STOPWORDS = "the and for from with les des une und der die das del los las van het".split(" ").toSet()

private val COMBINING = Regex("\\p{Mn}+")
private val NOT_LATIN = Regex("[^a-z0-9 ]")

/** [s] with its diacritics dropped, lower case, everything but a–z, 0–9 and spaces made a space. */
private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase(Locale.ROOT).replace(NOT_LATIN, " ")

/** The words of [name] the recognised text is checked for: folded, three letters or more (or a number), articles dropped, sorted. */
fun treatmentNameWords(name: String): List<String> =
    fold(name).split(' ').filter { it.isNotEmpty() && (it.length >= 3 || it.all(Char::isDigit)) && it !in STOPWORDS }.toSortedSet().toList()

/**
 * Whether the recognised [text] holds at least half of [name]'s words ([treatmentNameWords]), each
 * found inside the text (read with its spaces or without them, for a title set in spaced letters), or
 * as a word of it with one misread letter in three, or, four letters or more, inside the text's letters
 * run together with one misread letter in three (a logo read as one run, "ÖRDPINGS"). A name with no
 * such word matches any text.
 */
fun treatmentNameMatches(name: String, text: String): Boolean {
    val words = treatmentNameWords(name)
    if (words.isEmpty()) return true
    val folded = fold(text)
    // Read across the spaces too: a title set in spaced letters comes back split ("SH O GUN").
    val joined = folded.replace(" ", "")
    val tokens = folded.split(' ').filter { it.isNotEmpty() }
    val found = words.count { word ->
        word in folded || word in joined ||
            tokens.any { abs(it.length - word.length) * 3 <= word.length && edits(word, it) * 3 <= word.length } ||
            (word.length >= MIN_FUZZY_INSIDE && editsInside(word, joined) * 3 <= word.length)
    }
    return found * 2 >= words.size
}

/** A word this long or longer may be found misread inside a run of letters ([editsInside]); a shorter one would match too much. */
private const val MIN_FUZZY_INSIDE = 4

/** The fewest edits that make [word] appear somewhere inside [text]: the Levenshtein distance to its closest stretch of [text]. */
private fun editsInside(word: String, text: String): Int {
    // Edits to match word[0 until i] ending at each place in text; starting anywhere in text is free.
    var previous = IntArray(text.length + 1)
    for (i in 1..word.length) {
        val current = IntArray(text.length + 1)
        current[0] = i
        for (j in 1..text.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (word[i - 1] != text[j - 1]) 1 else 0)
        }
        previous = current
    }
    return previous.minOrNull() ?: word.length
}

/** The Levenshtein distance between [a] and [b]. */
private fun edits(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
        }
        previous = current
    }
    return previous[b.length]
}
