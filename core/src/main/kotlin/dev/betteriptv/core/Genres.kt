package dev.betteriptv.core

/**
 * TMDB's genres of movies and shows by their English names, with their names in the other App
 * languages (#242). Cached metadata names its genres in whichever language it was cached in, so
 * search keeps a genre by its English name, finds it by any of its names, and shows it in the App
 * language. A genre TMDB names the same in a language, or one not listed, keeps the name it has.
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
            "War & Politics" to "Guerre & Politique",
        ),
    )
    private val english: Map<String, String> = names.values.flatMap { it.entries }.associate { (english, named) -> named to english }
    private val all: Map<String, List<String>> = names.values.flatMap { it.entries }.groupBy({ it.key }, { it.value })

    /** The English name of genre [name], in whichever language it is named. */
    fun english(name: String): String = english[name] ?: name

    /** Genre [english] (as [Genres.english] names it) in [appLanguage]. */
    fun named(english: String, appLanguage: String): String = names[primaryLanguage(appLanguage)]?.get(english) ?: english

    /** Every name of genre [english], English first. */
    fun every(english: String): List<String> = listOf(english) + all[english].orEmpty()
}
