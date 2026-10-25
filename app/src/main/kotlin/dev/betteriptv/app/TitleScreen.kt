package dev.betteriptv.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.Backdrop
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.CertaintyLegend
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionLine
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.Library
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.Version

/**
 * A Title's details: what it is, and every Version grouped by language, the Viewer's languages
 * first and the rest behind "Other languages". TMDB's backdrop fills the screen behind them. Without
 * one, the poster sits beside the header instead: a poster blown up as a backdrop is too blurry and
 * too bright to keep text on it readable.
 */
@Composable
fun TitleScreen(library: Library, id: TitleId, contentLanguages: List<String>, appLanguage: String) {
    val details by produceState<TitleDetails?>(null, id, contentLanguages, appLanguage) {
        value = library.title(id, contentLanguages, appLanguage)
    }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        details?.let { TitleContent(it) }
    }
}

@Composable
private fun TitleContent(title: TitleDetails) {
    val context = LocalContext.current
    val backdrop = rememberArtwork(title.backdrop)
    val image = rememberArtwork(title.image).takeIf { backdrop == null }
    var showOthers by rememberSaveable(title.id) { mutableStateOf(false) }
    val firstVersion = remember { FocusRequester() }
    LaunchedEffect(title.id) { firstVersion.requestFocus() }
    // With no Version in the Viewer's languages, the other languages are all there is to show.
    val shown = title.languages.ifEmpty { title.otherLanguages }
    val best = shown.firstOrNull()?.versions?.firstOrNull()

    if (backdrop != null) Backdrop(backdrop)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            Row(Modifier.padding(top = Spacing.xl, bottom = Spacing.xl)) {
                Column(Modifier.width(470.dp)) {
                    Text(title.name, style = Type.display, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Spacing.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val versions = (title.languages + title.otherLanguages).sumOf { it.versionCount }
                        val count = pluralStringResource(R.plurals.versions_count, versions, versions)
                        Text(
                            listOfNotNull(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), title.genres.take(2).joinToString(", ").ifEmpty { null }, count)
                                .joinToString(" · "),
                            style = Type.body,
                            color = Colors.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(Spacing.m))
                        BadgeRow(best?.attributes?.cardBadges().orEmpty())
                    }
                    title.description?.let {
                        Spacer(Modifier.height(Spacing.s))
                        Text(it, style = Type.body, color = Colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (title.cast.isNotEmpty()) {
                        Text(
                            title.cast.take(4).joinToString(", "),
                            style = Type.body,
                            color = Colors.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (image != null) {
                    Image(
                        image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(Cards.posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(Corners.card)),
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.versions_title), style = Type.headline, color = Colors.ink)
                Spacer(Modifier.weight(1f))
                CertaintyLegend()
            }
        }
        item { VersionHeader(Modifier.padding(bottom = 2.dp)) }
        shown.forEachIndexed { index, group ->
            languageGroup(context, group, first = index == 0, firstVersion)
        }
        if (title.languages.isNotEmpty() && title.otherLanguages.isNotEmpty()) {
            // Other languages open under this row, so it keeps focus where it is.
            item(key = "other languages") {
                val others = title.otherLanguages.sumOf { it.versionCount }
                SettingRow(
                    stringResource(R.string.other_languages),
                    if (showOthers) stringResource(R.string.other_languages_hide) else stringResource(R.string.other_languages_show, others),
                    onClick = { showOthers = !showOthers },
                    modifier = Modifier.padding(top = Spacing.m),
                    detail = title.otherLanguages.joinToString(", ") { group -> group.language?.let(::languageName).orEmpty() },
                )
            }
            if (showOthers) title.otherLanguages.forEach { languageGroup(context, it, first = false, firstVersion) }
        }
        if (title.fromTmdb) {
            // TMDB's terms ask for this notice wherever its data is shown.
            item(key = "attribution") {
                Text(stringResource(R.string.tmdb_attribution), style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(top = Spacing.xl))
            }
        }
    }
}

/** `2 h 55 min`, or `48 min` under an hour. */
private fun Context.runtime(minutes: Int): String =
    if (minutes < 60) getString(R.string.runtime_minutes, minutes) else getString(R.string.runtime_hours, minutes / 60, minutes % 60)

private fun LazyListScope.languageGroup(context: Context, group: LanguageGroup, first: Boolean, firstVersion: FocusRequester) {
    item(key = "language ${group.language}") {
        Text(
            "${group.language?.let(::languageName) ?: context.getString(R.string.language_untagged)} · ${group.versionCount}",
            style = Type.title,
            color = Colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (first) 0.dp else Spacing.m, bottom = 2.dp),
        )
    }
    group.versions.forEachIndexed { index, version ->
        item(key = version.listingId) {
            VersionRow(
                context.line(version),
                // Playing a Version comes with the player.
                onClick = {},
                modifier = if (first && index == 0) Modifier.focusRequester(firstVersion) else Modifier,
            )
        }
    }
}

private fun Context.line(version: Version): VersionLine {
    val attributes = version.attributes
    val none = getString(R.string.version_none)
    return VersionLine(
        badges = attributes.versionBadges(),
        source = attributes.source?.value ?: none,
        audio = attributes.language?.value?.let(::languageName) ?: getString(R.string.language_untagged),
        subtitles = attributes.subtitles?.value?.joinToString(", ") { if (it == "mul") getString(R.string.subtitles_many) else languageName(it) } ?: none,
        bitrate = null,
        backups = version.backups.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backups_count, it, it) },
        // Both notes are cautions.
        note = when {
            attributes.cam -> getString(R.string.version_cam)
            attributes.audioDescription -> getString(R.string.version_audio_description)
            else -> null
        },
        noteIsCaution = true,
    )
}
