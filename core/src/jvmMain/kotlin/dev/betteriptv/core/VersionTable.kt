package dev.betteriptv.core

/** A column of the Version table, in the order they're drawn. */
enum class VersionColumn { Quality, Source, Audio, Subtitles, Bitrate }

/**
 * What the Versions screen's table shows (DESIGN.md, Version table), worked out before anything is
 * drawn so its rules are the same wherever it's drawn:
 * - an unknown cell is empty (null here), never a dash;
 * - a row's Audio cell is dropped when it only repeats its group's language heading ([VersionTableRow.audio]
 *   is then null), so it shows only when it adds something, such as `fr-CA` under Français;
 * - a column none of the rows has anything in is left out of [columns], so the others get its room.
 *
 * [columns] is worked out over every group the table may show, Other languages included, so opening
 * them never moves a column.
 */
data class VersionTable(val columns: List<VersionColumn>, val groups: List<VersionTableGroup>)

/** A language's Versions in the table: its heading's [language] (null for Untagged), each Version followed by its Backups. */
data class VersionTableGroup(val language: String?, val versionCount: Int, val rows: List<VersionTableRow>)

/**
 * One row of the table: a Version, or a [backup] of the one before it. [audio] is the main audio's
 * language when it says more than the group's heading, else null. [only] is the one language the
 * Version's audio is Confirmed to be in when that is neither a Content language nor the Title's
 * original ([VersionAttributes.onlyAudioLanguage]): the row then says "German only", what choosing
 * the 4K picture costs.
 */
data class VersionTableRow(val version: Version, val backup: Boolean, val audio: String?, val only: String? = null)

/**
 * The table for [groups], the Viewer's languages first, then the others (shown or not), in the order
 * given. With the Viewer's [contentLanguages] and the Title's [originalLanguage] (when Prefer original
 * audio acts on it), a Version whose Confirmed audio is in one other language is marked [VersionTableRow.only].
 */
fun versionTable(groups: List<LanguageGroup>, contentLanguages: List<String> = emptyList(), originalLanguage: String? = null): VersionTable {
    val tableGroups = groups.map { group ->
        val rows = group.versions.flatMap { version ->
            listOf(row(version, group, backup = false, contentLanguages, originalLanguage)) + version.backups.map { row(it, group, backup = true, contentLanguages, originalLanguage) }
        }
        VersionTableGroup(group.language, group.versionCount, rows)
    }
    val rows = tableGroups.flatMap { it.rows }
    val columns = VersionColumn.entries.filter { column -> rows.any { it.has(column) } }
    return VersionTable(columns, tableGroups)
}

private fun row(version: Version, group: LanguageGroup, backup: Boolean, contentLanguages: List<String>, originalLanguage: String?): VersionTableRow {
    // The heading is a primary language (`fr` holds `fr-CA`), or none for Untagged Versions.
    val audio = version.attributes.language?.value?.takeIf { it != group.language }
    val only = originalLanguage?.let { version.attributes.onlyAudioLanguage(contentLanguages, it) }
    return VersionTableRow(version, backup, audio, only)
}

private fun VersionTableRow.has(column: VersionColumn): Boolean = with(version.attributes) {
    when (column) {
        VersionColumn.Quality -> quality != null || hdr != null || dolbyAudio || cam || audioDescription
        VersionColumn.Source -> source != null
        VersionColumn.Audio -> audio != null || only != null
        VersionColumn.Subtitles -> !subtitles?.value.isNullOrEmpty()
        VersionColumn.Bitrate -> bitrate != null
    }
}
