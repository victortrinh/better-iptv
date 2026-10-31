package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.Locale

/**
 * A Title's TMDB metadata in one language, as the cloud serves it. Names and descriptions are
 * picked for the App language (a BCP 47 tag; `fr-CA` prefers Québec's translation), falling back
 * to English.
 */
internal class TitleMetadata(json: String) {
    private val tree = JsonReader.of(Buffer().writeUtf8(json)).use { it.readJsonValue() } as Map<*, *>

    /** The language the Title was released in (ISO 639-1), which an Untagged Version is Likely in. */
    val originalLanguage = tree["originalLanguage"] as? String
    private val originalTitle = (tree["originalTitle"] as? String).orEmpty()
    private val titles = texts("titles", "title")
    private val descriptions = texts("descriptions", "text")

    val genres: List<String> = strings(tree["genres"])
    val year: Int? = (tree["releaseDate"] as? String)?.take(4)?.toIntOrNull()
    val runtimeMinutes: Int? = (tree["runtimeMinutes"] as? Number)?.toInt()
    val poster: String? = image(tree["posterPath"], POSTER_SIZE)
    /** A poster with no text on it, for Home's cards, which lay the [cardLogo] over it; null when TMDB has none. */
    val textlessPoster: String? = image(tree["textlessPosterPath"], POSTER_SIZE)
    val backdrop: String? = image(tree["backdropPath"], BACKDROP_SIZE)
    /** The Title's logo art in the App language or English, as a PNG with transparency; null when TMDB has none. */
    val logo: String? = image((tree["logoPath"] as? String)?.replace(SVG, ".png"), LOGO_SIZE)
    /** The same logo for a card on Home, where it is about 110 dp wide. */
    val cardLogo: String? = image((tree["logoPath"] as? String)?.replace(SVG, ".png"), CARD_LOGO_SIZE)
    /** TMDB's popularity score: higher is more watched lately. */
    val popularity: Double = (tree["popularity"] as? Number)?.toDouble() ?: 0.0
    val cast: List<String> = (tree["cast"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }

    /** The Title's name in [appLanguage], else in English, else as it was released. */
    fun name(appLanguage: String): String =
        sequenceOf(appLanguage, "en").firstNotNullOfOrNull { language ->
            pick(titles, language) ?: originalTitle.takeIf { originalLanguage == primaryLanguage(language) && it.isNotEmpty() }
        } ?: originalTitle

    /** The description in [appLanguage], else in English; null when TMDB has neither. */
    fun description(appLanguage: String): String? = pick(descriptions, appLanguage) ?: pick(descriptions, "en")

    private class Text(val language: String, val region: String, val text: String)

    /** The text in [tag]'s language, from its region when there is one (`fr-CA`), else from any. */
    private fun pick(texts: List<Text>, tag: String): String? {
        val locale = Locale.forLanguageTag(tag)
        val same = texts.filter { it.language == primaryLanguage(tag) }
        return (same.find { it.region.equals(locale.country, ignoreCase = true) } ?: same.firstOrNull())?.text
    }

    private fun texts(field: String, textField: String): List<Text> = (tree[field] as? List<*>).orEmpty().mapNotNull {
        val entry = it as? Map<*, *> ?: return@mapNotNull null
        val text = (entry[textField] as? String)?.takeIf { text -> text.isNotBlank() } ?: return@mapNotNull null
        Text((entry["language"] as? String).orEmpty(), (entry["region"] as? String).orEmpty(), text)
    }

    private companion object {
        /** Cards and the details poster are 104 dp wide, 208 px on the stick. */
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w1280"
        /** The logo box is 320 dp wide, 640 px on the stick; TMDB's next size up is the original, often thousands wide. */
        const val LOGO_SIZE = "w500"
        /** 220 px on the stick. */
        const val CARD_LOGO_SIZE = "w300"
        /** TMDB serves an SVG logo as a PNG of the size asked for under the `.png` name, so no SVG decoder is needed. */
        val SVG = Regex("\\.svg$", RegexOption.IGNORE_CASE)

        fun strings(value: Any?) = (value as? List<*>).orEmpty().filterIsInstance<String>()

        /** TMDB's image CDN, which needs no API key. */
        fun image(path: Any?, size: String) = (path as? String)?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}
