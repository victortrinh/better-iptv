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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionLine
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.ShowEpisodes
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Title's details: what it is, Play with the Version it will start, and every Version grouped by
 * language, the Viewer's languages first and the rest behind "Other languages". A Show has no Play
 * yet; it lists its seasons and Episodes first, each Episode with the languages it's available in,
 * once the Provider's show details are read. TMDB's backdrop fills the screen behind them. Without
 * one, the poster sits beside the header instead: a poster blown up as a backdrop is too blurry and
 * too bright to keep text on it readable.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead.
 */
@Composable
fun TitleScreen(
    library: Library,
    id: TitleId,
    contentLanguages: List<String>,
    appLanguage: String,
    deviceProfile: () -> DeviceProfile,
    onPlay: (PlayPlan) -> Unit,
) {
    // The plan comes with the details, so first focus lands once, on Play or on the first Version.
    val loaded by produceState<Pair<TitleDetails, PlayPlan?>?>(null, id, contentLanguages, appLanguage) {
        val details = library.title(id, contentLanguages, appLanguage) ?: return@produceState
        value = details to library.playPlan(id, contentLanguages, withContext(Dispatchers.Default) { deviceProfile() })
    }
    val episodes by produceState<ShowEpisodes?>(null, id, contentLanguages) {
        if (id.kind == TitleKind.Show) value = library.episodes(id, contentLanguages)
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        loaded?.let { (title, plan) ->
            TitleContent(title, plan, episodes, onPlay, onChoose = { version ->
                scope.launch { library.chosenPlayPlan(id, version.listingId, contentLanguages)?.let(onPlay) }
            })
        }
    }
}

@Composable
private fun TitleContent(title: TitleDetails, plan: PlayPlan?, episodes: ShowEpisodes?, onPlay: (PlayPlan) -> Unit, onChoose: (Version) -> Unit) {
    val context = LocalContext.current
    val backdrop = rememberArtwork(title.backdrop)
    val image = rememberArtwork(title.image).takeIf { backdrop == null }
    var showOthers by rememberSaveable(title.id) { mutableStateOf(false) }
    val firstVersion = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val firstEpisode = remember { FocusRequester() }
    val isShow = title.id.kind == TitleKind.Show
    // Play takes first focus. With nothing to start, or on a Show, the first Version takes it, and a
    // Show's first Episode takes it over once the Episodes are read, unless the Viewer has moved on.
    var firstVersionFocused by remember { mutableStateOf(false) }
    LaunchedEffect(title.id) { (if (plan != null) play else firstVersion).requestFocus() }
    val hasEpisodes = episodes?.seasons?.isNotEmpty() == true
    LaunchedEffect(title.id, hasEpisodes) { if (hasEpisodes && firstVersionFocused) firstEpisode.requestFocus() }
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
                    // Playing an Episode comes later.
                    if (!isShow) {
                        Spacer(Modifier.height(Spacing.l))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                            NuageButton(
                                stringResource(R.string.play),
                                onClick = { plan?.let(onPlay) },
                                modifier = Modifier.focusRequester(play),
                                icon = Icons.Filled.PlayArrow,
                                enabled = plan != null,
                            )
                            Text(
                                plan?.version?.attributes?.playLabel(stringResource(R.string.language_untagged)) ?: stringResource(R.string.play_nothing),
                                style = Type.label,
                                color = Colors.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
        if (isShow) episodes(context, episodes, firstEpisode)
        item {
            Row(Modifier.padding(top = if (isShow) Spacing.xl else 0.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.versions_title), style = Type.headline, color = Colors.ink)
                Spacer(Modifier.weight(1f))
                CertaintyLegend()
            }
        }
        item { VersionHeader(Modifier.padding(bottom = 2.dp)) }
        shown.forEachIndexed { index, group ->
            languageGroup(context, group, first = index == 0, firstVersion, onChoose, onFirstFocused = { firstVersionFocused = it })
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
            if (showOthers) title.otherLanguages.forEach { languageGroup(context, it, first = false, firstVersion, onChoose) }
        }
        if (title.fromTmdb) {
            // TMDB's terms ask for this notice wherever its data is shown.
            item(key = "attribution") {
                Text(stringResource(R.string.tmdb_attribution), style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(top = Spacing.xl))
            }
        }
    }
}

/**
 * A Show's seasons, each Episode as a row naming the languages it's available in, the Viewer's
 * first. Playing an Episode comes with the player.
 */
private fun LazyListScope.episodes(context: Context, episodes: ShowEpisodes?, firstEpisode: FocusRequester) {
    item(key = "episodes") {
        Text(stringResource(R.string.episodes_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp))
    }
    val note = when {
        episodes == null -> context.getString(R.string.episodes_reading)
        episodes.seasons.isEmpty() && episodes.unreadVersions > 0 -> context.getString(R.string.episodes_unreachable)
        episodes.seasons.isEmpty() -> context.getString(R.string.episodes_none)
        episodes.unreadVersions > 0 -> context.resources.getQuantityString(R.plurals.episodes_unread, episodes.unreadVersions, episodes.unreadVersions)
        else -> null
    }
    if (note != null) item(key = "episodes note") { Text(note, style = Type.caption, color = Colors.inkMuted) }
    episodes?.seasons.orEmpty().forEachIndexed { seasonIndex, season ->
        item(key = "season ${season.number}") {
            Text(
                "${if (season.number == 0) context.getString(R.string.season_specials) else context.getString(R.string.season_title, season.number)} · " +
                    context.resources.getQuantityString(R.plurals.episodes_count, season.episodes.size, season.episodes.size),
                style = Type.title,
                color = Colors.ink,
                modifier = Modifier.padding(top = Spacing.m, bottom = 2.dp),
            )
        }
        season.episodes.forEachIndexed { index, episode ->
            item(key = "episode ${season.number} ${episode.number}") {
                SettingRow(
                    episode.name?.let { context.getString(R.string.episode_named, episode.number, it) } ?: context.getString(R.string.episode_number, episode.number),
                    (episode.languages + episode.otherLanguages).joinToString(", ") { group -> group.language?.let(::languageName) ?: context.getString(R.string.language_untagged) },
                    onClick = {},
                    modifier = if (seasonIndex == 0 && index == 0) Modifier.focusRequester(firstEpisode) else Modifier,
                )
            }
        }
    }
}

/** `2 h 55 min`, or `48 min` under an hour. */
private fun Context.runtime(minutes: Int): String =
    if (minutes < 60) getString(R.string.runtime_minutes, minutes) else getString(R.string.runtime_hours, minutes / 60, minutes % 60)

private fun LazyListScope.languageGroup(
    context: Context,
    group: LanguageGroup,
    first: Boolean,
    firstVersion: FocusRequester,
    onChoose: (Version) -> Unit,
    onFirstFocused: (Boolean) -> Unit = {},
) {
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
                onClick = { onChoose(version) },
                modifier = if (first && index == 0) Modifier.focusRequester(firstVersion).onFocusChanged { onFirstFocused(it.isFocused) } else Modifier,
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
