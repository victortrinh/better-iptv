package dev.betteriptv.core

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A Channel's card: the Provider's [name] for it, its logo [image] URL, and whether the Viewer keeps
 * it as a [favourite]. [id] is the Provider's stream id. [categoryId] is the Provider's id of the
 * Category the Listing was filed under (null when the Provider gave none), and [guideId] is what
 * the Provider's guide knows the Channel by, empty when the guide doesn't cover it.
 */
data class Channel(
    val id: Long,
    val name: String,
    val image: String?,
    val favourite: Boolean,
    val categoryId: String? = null,
    val guideId: String = "",
)

/**
 * A live Category as a Sync stored it: the Provider's [name] (`US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ`), its
 * [normalisedName] with the country code and the Provider's tags stripped (`NBC`), the [country]
 * code the name starts with (`US`, null when it has none) and its [kind]. [id] is the Provider's
 * category id.
 */
data class LiveCategory(val id: String, val name: String, val normalisedName: String, val country: String?, val kind: CategoryKind)

/** What a live Category holds: Channels to browse, Clutter (radio, karaoke, audiobooks), or an Event Category. */
enum class CategoryKind {
    Regular,
    Clutter,
    /** An Event Category: most of its Listings are Event channels or empty Event slots. */
    Event,
}

/**
 * A live Category's name without the Provider's dressing: the country code before a bar (`US| `,
 * `CA FR| `) and the superscript tags (`ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ`, `ⱽᴵᴾ`, `ᴳᴼᴸᴰ`) are dropped, the rest is
 * NFKC-normalised (fullwidth letters and ligatures become plain ones), spaces collapsed and trimmed.
 * `US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ` is "NBC". A name that is nothing but tags (`ᴬᴿᴱᴺᴬ`) keeps them as plain
 * letters ("ARENA") rather than vanish.
 */
internal fun normalisedCategoryName(name: String): String {
    val rest = name.replace(COUNTRY_CODE, "")
    fun plain(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(SPACES, " ").trim()
    return plain(rest.replace(SUPERSCRIPT_TAGS, " ")).ifEmpty { plain(rest) }.ifEmpty { name.trim() }
}

/** The country code a live Category's name starts with (`US` in `US| NBC`), or null. */
internal fun categoryCountry(name: String): String? = COUNTRY_CODE.find(name)?.groupValues?.get(1)?.replace(SPACES, " ")

/**
 * Whether a live Listing's name is an Event channel's: it carries a Game's start, or is marked
 * `Live |` or `Next |`. Numbered slots without a date (`UK: TNT SPORTS EVENT 12`) can't be told.
 */
internal fun isEventChannel(name: String, now: Instant): Boolean = MARKED_ON.containsMatchIn(name) || eventStart(name, now) != null

/** An Event channel counts as finished this long after the start its name gives: long enough for a match, a race or a fight card. */
internal val EVENT_FINISHED_AFTER: Duration = Duration.ofHours(6)

/**
 * When the Game an Event channel carries starts, from its [name], or null for a Channel that names
 * none. Names give it many ways (`(2026-10-29 18:00:30)`, `| 2026-10-28 | 15:00 (GMT)`,
 * `@ Oct 27 7:00 PM`, `Wed 28 Oct 16:20 CET`, `22/10 19:00`); the zone is seldom given and is taken
 * as UTC, which the 6 hours an Event shows for absorb. A date without a year is taken in the year
 * that puts it nearest [now]. A name marked `Live |` or `Next |` is on now or still to come
 * whatever its date says, so it gets none.
 */
internal fun eventStart(name: String, now: Instant): Instant? {
    if (MARKED_ON.containsMatchIn(name)) return null
    val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
    fun at(year: Int?, month: Int, day: Int, hour: Int, minute: Int, half: String? = null): Instant? {
        val hour24 = when {
            half == null -> hour
            half.equals("PM", ignoreCase = true) && hour < 12 -> hour + 12
            half.equals("AM", ignoreCase = true) && hour == 12 -> 0
            else -> hour
        }
        if (month !in 1..12 || day !in 1..31 || hour24 !in 0..23 || minute !in 0..59) return null
        val date = if (year != null) runCatching { LocalDate.of(year, month, day) }.getOrNull()
        else (today.year - 1..today.year + 1).mapNotNull { runCatching { LocalDate.of(it, month, day) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(today, it)) }
        return date?.let { LocalDateTime.of(it, java.time.LocalTime.of(hour24, minute)).toInstant(ZoneOffset.UTC) }
    }
    YEAR_FIRST.find(name)?.destructured?.let { (y, m, d, h, min) -> return at(y.toInt(), m.toInt(), d.toInt(), h.toInt(), min.toInt()) }
    DAY_FIRST.find(name)?.destructured?.let { (d, m, y, h, min) -> return at(y.toInt(), m.toInt(), d.toInt(), h.toInt(), min.toInt()) }
    MONTH_NAME_FIRST.find(name)?.destructured?.let { (m, d, h, min, half) -> return at(null, month(m), d.toInt(), h.toInt(), min.toInt(), half.ifEmpty { null }) }
    DAY_MONTH_NAME.find(name)?.destructured?.let { (d, m, h, min, half) -> return at(null, month(m), d.toInt(), h.toInt(), min.toInt(), half.ifEmpty { null }) }
    SLASHED.find(name)?.destructured?.let { (d, m, y, h, min) ->
        val year = y.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        return at(year, m.toInt(), d.toInt(), h.toInt(), min.toInt())
    }
    return null
}

/** Whether an Event that started at [start] is finished at [now]. */
internal fun finished(start: Instant?, now: Instant): Boolean = start != null && start.plus(EVENT_FINISHED_AFTER).isBefore(now)

private fun month(name: String) = MONTHS.indexOf(name.take(3).lowercase()) + 1

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private const val MONTH = "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?"

private val MARKED_ON = Regex("""^\s*(Live|Next)\s*\|""", RegexOption.IGNORE_CASE)
/** `US| `, `CA FR| `, `UK |`: a country code, maybe with a language, before a bar at the start of a Category's name. */
private val COUNTRY_CODE = Regex("""^\s*([A-Z]{2}(?:\s*[A-Z]{2})?)\s*\|\s*""")
/**
 * Runs of superscript and modifier letters and digits, the Provider's tags (`ᴴᴰ`, `ⱽᴵᴾ`, `⁶⁰ᶠᵖˢ`,
 * `ʰᵉᵛᶜ`), with the `/` that joins two of them. Small capitals (`ᴀʙᴄ`) are letters, not tags.
 */
private val SUPERSCRIPT_TAGS = Regex("""[\u00B9\u00B2\u00B3\u02B0-\u02B8\u02E0-\u02E4\u1D2C-\u1D6A\u1D78\u1D9B-\u1DBF\u2070-\u209C\u2C7C\u2C7D]+(?:\s*/\s*[\u00B9\u00B2\u00B3\u02B0-\u02B8\u02E0-\u02E4\u1D2C-\u1D6A\u1D78\u1D9B-\u1DBF\u2070-\u209C\u2C7C\u2C7D]+)*""")
private val SPACES = Regex("""\s+""")
private val YEAR_FIRST = Regex("""(20\d\d)-(\d\d)-(\d\d)\s*\|?\s*(\d{1,2}):(\d\d)""")
private val DAY_FIRST = Regex("""(?<!\d)(\d\d)-(\d\d)-(20\d\d)\s*\|?\s*(\d{1,2}):(\d\d)""")
private val MONTH_NAME_FIRST = Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{1,2}):(\d\d)\s*(AM|PM|am|pm)?""", RegexOption.IGNORE_CASE)
private val DAY_MONTH_NAME = Regex("""(?<![\d/])(\d{1,2})\s+$MONTH\s+(\d{1,2}):(\d\d)\s*(AM|PM|am|pm)?""", RegexOption.IGNORE_CASE)
private val SLASHED = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\s+(\d{1,2}):(\d\d)""")
