package dev.betteriptv.core

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A Channel: a live broadcast as one thing, however many Listings the Provider has for it (CONTEXT.md).
 * [key] is its identity, the country tag and the normalised name (`UK: BBC ONE NI`, case folded),
 * which a Favourite is kept by; [name] is the Provider's name for it without its Tier tag, country
 * tag and quality words (`BBC ONE NI`), and [country] the country tag (`UK`, `CA FR`) for the
 * caption, null when the name has none. [image] is the first variant's logo URL, [favourite]
 * whether the Viewer keeps the Channel, and [variants] its Listings in the Provider's order.
 */
data class Channel(
    val key: String,
    val name: String,
    val country: String?,
    val image: String?,
    val favourite: Boolean,
    val variants: List<ChannelVariant>,
) {
    /** What the Provider's guide knows the Channel by: the first variant's guide id, empty when the guide covers none. */
    val guideId: String get() = variants.firstOrNull { it.guideId.isNotEmpty() }?.guideId.orEmpty()

    /** The Provider's ids of every Category the Channel has a variant in, in the Provider's order. */
    val categoryIds: List<String> get() = variants.mapNotNull { it.categoryId }.distinct()
}

/**
 * One Listing of a Channel (CONTEXT.md's Channel variant): [id] is the Provider's stream id and
 * [name] its name as the Provider lists it (`VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ`). [quality] is what the name
 * says it is, Likely (UHD for 4K, HD for HD, FHD and RAW, SD), null when it says nothing, and
 * [hevc] whether the name says HEVC. [categoryId] is the Provider's id of the Category the Listing
 * was filed under (null when the Provider gave none), and [guideId] is what the Provider's guide
 * knows it by, empty when the guide doesn't cover it.
 */
data class ChannelVariant(
    val id: Long,
    val name: String,
    val quality: Quality?,
    val hevc: Boolean,
    val categoryId: String? = null,
    val guideId: String = "",
)

/**
 * [variants] best first, as watching the Channel tries them: by quality under the [qualityCap]
 * (4K, then HD, then SD, then ones whose names say nothing), HEVC first within a quality when the
 * [device] decodes it in hardware and last when it doesn't, then in the Provider's order. Variants
 * above the cap, or taller than the TV displays, come after every other, the lowest first, so they
 * play only when nothing else does.
 */
internal fun rankedVariants(variants: List<ChannelVariant>, device: DeviceProfile, qualityCap: QualityCap): List<ChannelVariant> {
    val decodesHevc = HEVC in device.videoDecoders
    fun over(variant: ChannelVariant): Boolean {
        val quality = variant.quality ?: return false
        return quality > qualityCap.quality || !device.canDisplay(VersionAttributes(quality = Known(quality, Provenance.Likely)))
    }
    return variants.sortedWith(
        compareBy<ChannelVariant>(::over)
            .thenBy { if (over(it)) it.quality!!.ordinal else -(it.quality?.ordinal ?: -1) }
            .thenBy { if (decodesHevc) !it.hevc else it.hevc },
    )
}

/** HEVC's MIME type, as [DeviceProfile.videoDecoders] names the formats the stick decodes. */
internal const val HEVC = "video/hevc"

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
 * Whether a live Listing is an Event channel: its [name] carries a Game's [start] (as [eventStart]
 * read it), or is marked `Live |` or `Next |`. Numbered slots without a date (`UK: TNT SPORTS
 * EVENT 12`) can't be told.
 */
internal fun isEventChannel(name: String, start: Instant?): Boolean = start != null || MARKED_ON.containsMatchIn(name)

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

/**
 * A country the Channel browser lists (the Channels row on Live): its ISO 3166 [code] (`GB` for the
 * Provider's `UK`; `CA EN` and `CA FR` Categories are one Canada), as the App language names it,
 * and how many [channels] its regular Categories hold, a Channel in two of them counted once.
 */
data class LiveCountry(val code: String, val channels: Int)

/**
 * A Category as the Channel browser lists it in a country's column: its [name] without the country
 * code and the Provider's tags (`NBC`, not `US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ`), the Provider's [categoryIds]
 * it merges (the `ⱽᴵᴾ` and `ᴳᴼᴸᴰ` Categories that share a name), and how many [channels] they hold
 * between them, each Channel once.
 */
data class BrowsedCategory(val name: String, val categoryIds: List<String>, val channels: Int)

/**
 * The ISO 3166 code of a live Category's [country] code (`US| `, `CA FR| `, `UK| `): its first two
 * letters, so a language after the country (`CA FR`) still files under the country, and the
 * Provider's `UK` is `GB`, which is what names it in the App language.
 */
internal fun isoCountry(country: String): String = country.take(2).let { if (it == "UK") "GB" else it }

/**
 * The browser's countries in order: the Viewer's [region] first, then the countries of their
 * [contentLanguages] in order ([languageCountries]), each only when the Provider has it, then the
 * rest by Channel count, the fullest first (ties by code). [counts] is each country's Channel count
 * by ISO code.
 */
internal fun orderedCountries(counts: Map<String, Int>, region: String, contentLanguages: List<String>): List<LiveCountry> {
    val preferred = (listOf(region) + contentLanguages.flatMap(::languageCountries)).filter { it in counts }
    val byCount = counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
    return (preferred + byCount).distinct().map { LiveCountry(it, counts.getValue(it)) }
}
