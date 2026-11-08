package dev.betteriptv.core

/**
 * The genres of Movies and Shows by their English names (as the Provider's genre fields give them),
 * with their names in the other App languages (#242). The Prefix table's
 * `[genre categories]` section maps the Provider's genre words in every surveyed language to these
 * names ([PrefixTable.genres]), so a genre is kept by its English name, found by any of its names,
 * and shown in the App language. A genre named the same in a language, or one not listed, keeps the
 * name it has.
 */
internal object Genres {
    private val names: Map<String, Map<String, String>> = mapOf(
        "fr" to mapOf(
            "Adventure" to "Aventure",
            "Comedy" to "Comédie",
            "Documentary" to "Documentaire",
            "Drama" to "Drame",
            "Family" to "Familial",
            "Fantasy" to "Fantastique",
            "History" to "Histoire",
            "Horror" to "Horreur",
            "Music" to "Musique",
            "Mystery" to "Mystère",
            "Science Fiction" to "Science-Fiction",
            "TV Movie" to "Téléfilm",
            "War" to "Guerre",
            "Sci-Fi & Fantasy" to "Science-Fiction & Fantastique",
            "Reality" to "Réalité",
        ),
    )
    private val english: Map<String, String> = names.values.flatMap { it.entries }.associate { (english, named) -> named to english }
    private val all: Map<String, List<String>> = names.values.flatMap { it.entries }.groupBy({ it.key }, { it.value })

    /**
     * The Prefix table's genres that describe Channels and Categories, not Titles (`EN - DRAMA
     * MOVIES` is filed under Movies; a Title isn't a Movie by genre), and the table genres whose
     * Titles go by another name: the Kids words file Family Titles, the Documentaries words
     * Documentary ones.
     */
    private val liveOnly = setOf("News", "Sports", "Entertainment", "General", "Movies", "Series", "Lifestyle", "Local", "Religion")
    private val titleNames = mapOf("Kids" to "Family", "Documentaries" to "Documentary")

    /** The Title genre a Prefix table genre names, in English, or null for a genre that describes Channels only. */
    fun titleGenre(tableGenre: String): String? = if (tableGenre in liveOnly) null else titleNames[tableGenre] ?: tableGenre

    /** Whether a Prefix table genre ranks live Categories in the Channel browser ([PrefixTable.genreRank]): the Title genres (Drama, Comedy) don't. */
    fun ranksChannels(tableGenre: String): Boolean = tableGenre in liveOnly || tableGenre in titleNames || tableGenre == "Music"

    /** The English name of genre [name], in whichever language it is named. */
    fun english(name: String): String = english[name] ?: name

    /** Genre [english] (as [Genres.english] names it) in [appLanguage]. */
    fun named(english: String, appLanguage: String): String = names[primaryLanguage(appLanguage)]?.get(english) ?: english

    /** Every name of genre [english], English first. */
    fun every(english: String): List<String> = listOf(english) + all[english].orEmpty()
}
