package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Versions screen's table, before anything is drawn: which columns show and which cells are left empty. */
class VersionTableTest {
    private fun likely(language: String) = Known(language, Provenance.Likely)
    private fun version(id: Long, attributes: VersionAttributes, backups: List<Version> = emptyList()) = Version(id, "Heat $id", attributes, backups)

    @Test fun `the Audio cell is dropped when it only repeats the language heading, and kept when it says more`() {
        val english = version(1, VersionAttributes(language = likely("en")))
        val quebec = version(2, VersionAttributes(language = likely("fr-CA")))
        val france = version(3, VersionAttributes(language = likely("fr")))
        val untagged = version(4, VersionAttributes())

        val table = versionTable(listOf(LanguageGroup("en", listOf(english)), LanguageGroup("fr", listOf(quebec, france)), LanguageGroup(null, listOf(untagged))))

        assertEquals(listOf(null, "fr-CA", null, null), table.groups.flatMap { group -> group.rows.map { it.audio } })
        // One row says more than its heading, so the column stays.
        assertEquals(listOf(VersionColumn.Audio), table.columns)
    }

    @Test fun `a Version whose Confirmed audio is in one language that is neither the Viewer's nor the original is marked only`() {
        val confirmed = { languages: Set<String> -> Known(languages, Provenance.Confirmed) }
        val german = version(1, VersionAttributes(language = Known("de", Provenance.Confirmed), audioLanguages = confirmed(setOf("de"))))
        val germanAndMandarin = version(2, VersionAttributes(language = Known("de", Provenance.Confirmed), audioLanguages = confirmed(setOf("de", "zh"))))
        val germanLikely = version(3, VersionAttributes(language = likely("de")))
        val mandarin = version(4, VersionAttributes(language = Known("zh", Provenance.Confirmed), audioLanguages = confirmed(setOf("zh"))))
        val english = version(5, VersionAttributes(language = Known("en", Provenance.Confirmed), audioLanguages = confirmed(setOf("en"))))
        val groups = listOf(LanguageGroup("en", listOf(english)), LanguageGroup("zh", listOf(mandarin)), LanguageGroup("de", listOf(german, germanAndMandarin, germanLikely)))

        val table = versionTable(groups, listOf("en"), originalLanguage = "zh")

        assertEquals(listOf(null, null, "de", null, null), table.groups.flatMap { group -> group.rows.map { it.only } })
        assertEquals(listOf(VersionColumn.Audio), table.columns)
        // Without the original language (the setting off), nothing is marked.
        assertEquals(emptyList<VersionColumn>(), versionTable(groups, listOf("en")).columns)
    }

    @Test fun `a column nothing is known for is hidden, so the others get its room`() {
        val versions = listOf(
            version(1, VersionAttributes(language = likely("en"), quality = Known(Quality.FullHD, Provenance.Likely), source = Known("Netflix", Provenance.Likely))),
            version(2, VersionAttributes(language = likely("en"), bitrate = Known(8_000_000L, Provenance.Confirmed))),
        )

        val table = versionTable(listOf(LanguageGroup("en", versions)))

        // Every row's audio repeats "English", and none has subtitles.
        assertEquals(listOf(VersionColumn.Quality, VersionColumn.Source, VersionColumn.Bitrate), table.columns)
    }

    @Test fun `the columns count the other languages too, so opening them never moves one`() {
        val english = version(1, VersionAttributes(language = likely("en")))
        val german = version(2, VersionAttributes(language = likely("de"), subtitles = Known(setOf("en"), Provenance.Confirmed)))

        val table = versionTable(listOf(LanguageGroup("en", listOf(english)), LanguageGroup("de", listOf(german))))

        assertEquals(listOf(VersionColumn.Subtitles), table.columns)
    }

    @Test fun `each Version is followed by its Backups, noted as such`() {
        val backup = version(2, VersionAttributes(language = likely("en"), quality = Known(Quality.FullHD, Provenance.Likely)))
        val leader = version(1, VersionAttributes(language = likely("en"), quality = Known(Quality.FullHD, Provenance.Likely)), backups = listOf(backup))
        val other = version(3, VersionAttributes(language = likely("en")))

        val group = versionTable(listOf(LanguageGroup("en", listOf(leader, other)))).groups.single()

        assertEquals(listOf(1L to false, 2L to true, 3L to false), group.rows.map { it.version.listingId to it.backup })
        assertEquals(3, group.versionCount)
    }

    @Test fun `an empty subtitle set counts as nothing known`() {
        val table = versionTable(listOf(LanguageGroup("en", listOf(version(1, VersionAttributes(language = likely("en"), subtitles = Known(emptySet(), Provenance.Likely)))))))

        assertEquals(emptyList<VersionColumn>(), table.columns)
    }
}
