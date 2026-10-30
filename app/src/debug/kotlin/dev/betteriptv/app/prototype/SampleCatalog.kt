package dev.betteriptv.app.prototype

import androidx.annotation.DrawableRes
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.components.BadgeSpec
import dev.betteriptv.app.ui.components.Certainty.Confirmed
import dev.betteriptv.app.ui.components.Certainty.Likely
import dev.betteriptv.app.ui.components.VersionLine

/**
 * Placeholder content for the design prototypes (#3). The films are real and their posters are public
 * domain (Wikimedia Commons); every Version, badge and number below is made up.
 */
data class SampleTitle(
    val name: String,
    val year: Int,
    val runtime: String,
    val genres: String,
    val people: String,
    val description: String,
    @DrawableRes val poster: Int,
    @DrawableRes val still: Int,
    val badges: List<BadgeSpec>,
)

object SampleCatalog {
    val general = SampleTitle(
        "The General", 1926, "1 h 18", "Comedy · Action", "Buster Keaton, Marion Mack",
        "Union spies steal his beloved locomotive, so a Southern engineer chases them alone across enemy lines.",
        R.drawable.proto_poster_general,
        R.drawable.proto_still_general,
        listOf(BadgeSpec("4K", Confirmed), BadgeSpec("HDR", Likely), BadgeSpec("EN", Confirmed)),
    )
    val metropolis = SampleTitle(
        "Metropolis", 1927, "2 h 33", "Science fiction", "Brigitte Helm, Gustav Fröhlich",
        "In a city of towers built on the labour of workers below, the son of its master falls for a woman who preaches hope.",
        R.drawable.proto_poster_metropolis,
        R.drawable.proto_still_metropolis,
        listOf(BadgeSpec("1080p", Confirmed), BadgeSpec("FR", Likely), BadgeSpec("EN", Confirmed)),
    )
    val safetyLast = SampleTitle(
        "Safety Last!", 1923, "1 h 10", "Comedy", "Harold Lloyd, Mildred Davis",
        "A small-town boy in the big city climbs a department store wall to win a prize, and his girl.",
        R.drawable.proto_poster_safety,
        R.drawable.proto_still_safety,
        listOf(BadgeSpec("4K", Likely), BadgeSpec("EN", Likely)),
    )
    val sherlockJr = SampleTitle(
        "Sherlock Jr.", 1924, "45 min", "Comedy", "Buster Keaton, Kathryn McGuire",
        "A projectionist who dreams of being a detective walks into the film he is showing.",
        R.drawable.proto_poster_sherlock,
        R.drawable.proto_still_sherlock,
        listOf(BadgeSpec("1080p", Confirmed), BadgeSpec("EN", Confirmed), BadgeSpec("FR", Confirmed)),
    )
    val kid = SampleTitle(
        "The Kid", 1921, "1 h 08", "Comedy · Drama", "Charlie Chaplin, Jackie Coogan",
        "The Tramp takes in an abandoned child, and five years later the authorities come for him.",
        R.drawable.proto_poster_kid,
        R.drawable.proto_still_kid,
        listOf(BadgeSpec("1080p", Confirmed), BadgeSpec("EN", Confirmed)),
    )
    val goldRush = SampleTitle(
        "The Gold Rush", 1925, "1 h 35", "Comedy · Adventure", "Charlie Chaplin, Georgia Hale",
        "A lone prospector braves the Klondike, a snowbound cabin and a starving partner.",
        R.drawable.proto_poster_goldrush,
        R.drawable.proto_still_goldrush,
        listOf(BadgeSpec("4K", Confirmed), BadgeSpec("DV", Confirmed), BadgeSpec("FR", Likely)),
    )
    val phantom = SampleTitle(
        "The Phantom of the Opera", 1925, "1 h 33", "Horror", "Lon Chaney, Mary Philbin",
        "A masked figure haunting the Paris Opera grooms a young soprano for stardom.",
        R.drawable.proto_poster_phantom,
        R.drawable.proto_still_phantom,
        listOf(BadgeSpec("720p", Likely), BadgeSpec("EN", Confirmed)),
    )
    val caligari = SampleTitle(
        "The Cabinet of Dr. Caligari", 1920, "1 h 16", "Horror", "Werner Krauss, Conrad Veidt",
        "A fairground hypnotist and his sleepwalker arrive in a town, and the murders begin.",
        R.drawable.proto_poster_caligari,
        R.drawable.proto_still_caligari,
        listOf(BadgeSpec("1080p", Likely), BadgeSpec("DE", Likely)),
    )

    val featured = general

    data class Row(val heading: String, val titles: List<SampleTitle>, val progress: List<Float>? = null, val left: List<String>? = null)

    val rows = listOf(
        Row(
            "Continue watching",
            listOf(metropolis, sherlockJr, phantom, kid),
            progress = listOf(0.62f, 0.2f, 0.85f, 0.4f),
            left = listOf("58 min left", "36 min left", "14 min left", "41 min left"),
        ),
        Row("New on your service", listOf(goldRush, safetyLast, caligari, kid, metropolis, sherlockJr, phantom)),
        Row("Comedy", listOf(safetyLast, kid, goldRush, sherlockJr, general)),
    )

    data class LanguageGroup(val language: String, val lines: List<VersionLine>)

    /** The General's Versions, grouped by language, best first. */
    val versions = listOf(
        LanguageGroup(
            "English",
            listOf(
                VersionLine(
                    listOf(BadgeSpec("4K", Confirmed), BadgeSpec("HDR10", Confirmed)),
                    "Netflix", "English 5.1, Français, Español +4", "10 subtitles", "18 Mbps", "2 Backups",
                ),
                VersionLine(
                    listOf(BadgeSpec("1080p", Likely), BadgeSpec("EN", Likely)),
                    "Prime Video", "Not read yet", "Not read yet", null, null,
                    note = "Likely English",
                ),
                VersionLine(
                    listOf(BadgeSpec("720p", Confirmed)),
                    "Unknown", "English stereo", "None", "2 Mbps", null,
                    note = "Cinema recording (CAM)", noteIsCaution = true,
                ),
            ),
        ),
        LanguageGroup(
            "Français",
            listOf(
                VersionLine(
                    listOf(BadgeSpec("4K", Confirmed), BadgeSpec("DV", Confirmed)),
                    "Disney+", "Français (Québec) 5.1, English", "4 subtitles", "22 Mbps", "1 Backup",
                    note = "Français (Québec)",
                ),
                VersionLine(
                    listOf(BadgeSpec("1080p", Likely), BadgeSpec("FR", Likely)),
                    "Netflix", "Not read yet", "Not read yet", null, null,
                    note = "Français (France)",
                ),
                VersionLine(
                    listOf(BadgeSpec("1080p", Confirmed)),
                    "Canal+", "Français, audio description", "1 subtitle", "6 Mbps", null,
                    note = "Audio description, never picked automatically", noteIsCaution = true,
                ),
            ),
        ),
    )
    const val otherLanguages = "Other languages: Español, Deutsch, Italiano, Português"
}
