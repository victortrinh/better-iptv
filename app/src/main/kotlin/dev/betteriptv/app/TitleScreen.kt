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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.betteriptv.app.ui.components.ActionRow
import dev.betteriptv.app.ui.components.PrimaryAction
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.Library
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.ShowEpisodes
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Title's details: what it is, a list of actions led by Play with the Version it will start, and
 * every Version grouped by language, the Viewer's languages first and the rest behind "Other
 * languages". A Show lists its seasons and Episodes first, each Episode with the languages it's
 * available in, once the Provider's show details are read, and choosing one plays it. TMDB's
 * backdrop fills the screen behind them. Without one, the poster sits beside the header instead: a
 * poster blown up as a backdrop is too blurry and too bright to keep text on it readable.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead. On a Movie the Viewer stopped in,
 * Play becomes "Continue from 1:02:13", with Restart and "Remove from Continue watching" under it.
 * A Show's Play names the Episode it plays ("Continue S1 E6", or "Play S1 E1" when not started), and
 * the header names that Episode.
 */
@Composable
fun TitleScreen(
    library: Library,
    id: TitleId,
    contentLanguages: List<String>,
    appLanguage: String,
    qualityCap: QualityCap,
    deviceProfile: () -> DeviceProfile,
    onPlay: (PlayPlan, String) -> Unit,
) {
    // Each Header read that Confirms something reloads the details and the plan, in place.
    var reads by remember(id) { mutableIntStateOf(0) }
    // Removing it from Continue watching reloads the plan too, which then starts from the beginning.
    var removals by remember(id) { mutableIntStateOf(0) }
    // The plan comes with the details, so first focus lands once, on Play or on the first Version.
    val loaded by produceState<Pair<TitleDetails, PlayPlan?>?>(null, id, contentLanguages, appLanguage, qualityCap, reads, removals) {
        val details = library.title(id, contentLanguages, appLanguage) ?: return@produceState
        value = details to library.playPlan(id, contentLanguages, withContext(Dispatchers.Default) { deviceProfile() }, qualityCap)
    }
    val episodes by produceState<ShowEpisodes?>(null, id, contentLanguages, reads) {
        if (id.kind == TitleKind.Show) value = library.episodes(id, contentLanguages)
    }
    // A Show's reads need its show details, so they start once its Episodes are in.
    val readable = id.kind == TitleKind.Movie || episodes != null
    // A Show's Play needs its Episodes too. Whether the Viewer has played any of it says Continue or Play.
    val upNext by produceState<Pair<PlayPlan, Boolean>?>(null, id, episodes, qualityCap) {
        if (episodes?.seasons.isNullOrEmpty()) return@produceState
        val device = withContext(Dispatchers.Default) { deviceProfile() }
        value = library.upNext(id, contentLanguages, device, qualityCap)?.let { it to library.episodeProgress(id).isNotEmpty() }
    }
    LaunchedEffect(id, contentLanguages, readable) { if (readable) library.readHeaders(id, contentLanguages) { reads++ } }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        loaded?.let { (title, moviePlan) ->
            TitleContent(
                title, moviePlan ?: upNext?.first, episodes,
                started = upNext?.second == true,
                onPlay = { onPlay(it, title.name) },
                onRemove = { scope.launch { library.removeFromContinueWatching(id); removals++ } },
                onChoose = { version ->
                    scope.launch { library.chosenPlayPlan(id, version.listingId, contentLanguages)?.let { onPlay(it, title.name) } }
                },
                onEpisode = { episode ->
                    scope.launch {
                        val device = withContext(Dispatchers.Default) { deviceProfile() }
                        library.episodePlayPlan(id, EpisodeNumber(episode.season, episode.number), contentLanguages, device, qualityCap)?.let { onPlay(it, title.name) }
                    }
                },
            )
        }
    }
}

@Composable
private fun TitleContent(
    title: TitleDetails,
    plan: PlayPlan?,
    episodes: ShowEpisodes?,
    started: Boolean,
    onPlay: (PlayPlan) -> Unit,
    onRemove: () -> Unit,
    onChoose: (Version) -> Unit,
    onEpisode: (Episode) -> Unit,
) {
    val context = LocalContext.current
    val backdrop = rememberArtwork(title.backdrop)
    val image = rememberArtwork(title.image).takeIf { backdrop == null }
    var showOthers by rememberSaveable(title.id) { mutableStateOf(false) }
    val firstVersion = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val firstEpisode = remember { FocusRequester() }
    val isShow = title.id.kind == TitleKind.Show
    // Play takes first focus. With nothing to start, the first Version takes it. A Show's Play comes
    // once its Episodes are read, and takes focus over from the first Version then, unless the Viewer
    // has moved on; with no Episode to play, its first Episode does.
    var firstVersionFocused by remember { mutableStateOf(false) }
    val hasPlay = plan != null || !isShow
    LaunchedEffect(title.id) { (if (plan != null) play else firstVersion).requestFocus() }
    LaunchedEffect(title.id, hasPlay) { if (isShow && hasPlay && firstVersionFocused) play.requestFocus() }
    // Removing it from Continue watching takes its row away, so focus goes back to Play.
    val resumable = plan != null && plan.from > 0
    var wasResumable by remember(title.id) { mutableStateOf(resumable) }
    LaunchedEffect(resumable) {
        if (wasResumable && !resumable) play.requestFocus()
        wasResumable = resumable
    }
    val hasEpisodes = episodes?.seasons?.isNotEmpty() == true
    LaunchedEffect(title.id, hasEpisodes) { if (hasEpisodes && !hasPlay && firstVersionFocused) firstEpisode.requestFocus() }
    val episode = plan?.episode?.let { number ->
        episodes?.seasons?.firstOrNull { it.number == number.season }?.episodes?.firstOrNull { it.number == number.number }
    }
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
                    // A Show's header names the Episode its Play starts.
                    if (plan?.episode != null) {
                        val number = plan.episode!!
                        Text(
                            episode?.name?.let { stringResource(R.string.details_episode_named, number.season, number.number, it) }
                                ?: stringResource(R.string.details_episode, number.season, number.number),
                            style = Type.title,
                            color = Colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.s),
                        )
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
                    // A Show's Play waits for its Episodes.
                    if (hasPlay) {
                        Spacer(Modifier.height(Spacing.l))
                        val number = plan?.episode
                        val text = when {
                            number != null && (started || resumable) -> stringResource(R.string.play_continue_episode, number.season, number.number)
                            number != null -> stringResource(R.string.play_episode, number.season, number.number)
                            resumable -> stringResource(R.string.play_continue, clock(plan!!.from))
                            else -> stringResource(R.string.play)
                        }
                        Column(Modifier.width(ACTIONS_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            PrimaryAction(
                                text,
                                Icons.Filled.PlayArrow,
                                onClick = { plan?.let(onPlay) },
                                modifier = Modifier.focusRequester(play),
                                progress = plan?.progress,
                                enabled = plan != null,
                            )
                            if (resumable) {
                                ActionRow(stringResource(R.string.play_restart), Icons.Filled.Refresh, onClick = { plan?.let { onPlay(it.fromStart()) } })
                                // Continue watching holds Movies: an Episode's place is kept with the Show.
                                if (!isShow) ActionRow(stringResource(R.string.continue_watching_remove), Icons.Filled.Close, onClick = onRemove)
                            }
                        }
                        Spacer(Modifier.height(Spacing.s))
                        PlayLabel(plan?.version?.attributes?.playLabel(stringResource(R.string.language_untagged)) ?: stringResource(R.string.play_nothing))
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
        if (isShow) episodes(context, episodes, firstEpisode, onEpisode)
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
 * first. Choosing one plays its Best playable Version.
 */
private fun LazyListScope.episodes(context: Context, episodes: ShowEpisodes?, firstEpisode: FocusRequester, onEpisode: (Episode) -> Unit) {
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
                    onClick = { onEpisode(episode) },
                    modifier = if (seasonIndex == 0 && index == 0) Modifier.focusRequester(firstEpisode) else Modifier,
                )
            }
        }
    }
}

/** The action list's width: rows of one width, sized for "Remove from Continue watching" and a progress bar beside "Reprendre à 1:02:13". */
private val ACTIONS_WIDTH = 360.dp

/** The muted line naming the Version Play starts. */
@Composable
private fun PlayLabel(text: String) {
    Text(text, style = Type.label, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** `2 h 55 min`, or `48 min` under an hour. */
internal fun Context.runtime(minutes: Int): String =
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
                context.versionLine(version),
                onClick = { onChoose(version) },
                modifier = if (first && index == 0) Modifier.focusRequester(firstVersion).onFocusChanged { onFirstFocused(it.isFocused) } else Modifier,
            )
        }
    }
}
