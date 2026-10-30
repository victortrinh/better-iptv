package dev.betteriptv.app

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
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
import dev.betteriptv.app.ui.components.NuageButton
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
 * A Title's details: what it is, Play with the Version it will start, and every Version grouped by
 * language, the Viewer's languages first and the rest behind "Other languages". A Show has no Play
 * button; it lists its seasons and Episodes first, each Episode with the languages it's available
 * in and how far the Viewer got, once the Provider's show details are read, and choosing one plays
 * it. Focus starts on the Episode to watch next, the one before it peeking above. Episodes TMDB lists
 * as not aired yet say when they will be, and can't be played. TMDB's backdrop fills the screen behind them. Without
 * one, the poster sits beside the header instead: a poster blown up as a backdrop is too blurry and
 * too bright to keep text on it readable.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead. On a Movie the Viewer stopped in,
 * Play becomes "Continue from 1:02:13" beside Restart and "Remove from Continue watching".
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
    val episodes by produceState<ShowEpisodes?>(null, id, contentLanguages, appLanguage, reads) {
        if (id.kind == TitleKind.Show) value = library.episodes(id, contentLanguages, appLanguage)
    }
    // A Show's reads need its show details, so they start once its Episodes are in.
    val readable = id.kind == TitleKind.Movie || episodes != null
    LaunchedEffect(id, contentLanguages, readable) { if (readable) library.readHeaders(id, contentLanguages) { reads++ } }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        loaded?.let { (title, plan) ->
            TitleContent(
                title, plan, episodes,
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
    val nextEpisode = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val isShow = title.id.kind == TitleKind.Show
    // Play takes first focus. With nothing to start, or on a Show, the first Version takes it, and a
    // Show's next Episode takes it over once the Episodes are read, unless the Viewer has moved on.
    var firstVersionFocused by remember { mutableStateOf(false) }
    // When the Episodes come first, the first Version isn't laid out yet and nothing takes focus.
    var listHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(title.id) { (if (plan != null) play else firstVersion).requestFocus() }
    // Removing it from Continue watching takes its button away, so focus goes back to Play.
    val resumable = plan != null && plan.from > 0
    var wasResumable by remember(title.id) { mutableStateOf(resumable) }
    LaunchedEffect(resumable) {
        if (wasResumable && !resumable) play.requestFocus()
        wasResumable = resumable
    }
    val next = episodes?.next
    val peekPx = with(LocalDensity.current) { PEEK.roundToPx() }
    LaunchedEffect(title.id, next) {
        if (episodes == null || next == null || (listHasFocus && !firstVersionFocused)) return@LaunchedEffect
        // The Episode before the next one peeks above it, so the Viewer sees where they are in the season.
        // A list opens here once; from then on EdgeScroll moves it.
        episodes.previousEpisodeIndex(next)?.let { listState.scrollToItem(it, peekPx) }
        withFrameNanos {}
        nextEpisode.requestFocus()
    }
    // With no Version in the Viewer's languages, the other languages are all there is to show.
    val shown = title.languages.ifEmpty { title.otherLanguages }
    val best = shown.firstOrNull()?.versions?.firstOrNull()

    if (backdrop != null) Backdrop(backdrop)
    LazyColumn(
        Modifier.fillMaxSize().onFocusChanged { listHasFocus = it.hasFocus },
        state = listState,
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
                    // A Show plays from its Episodes.
                    if (!isShow) {
                        Spacer(Modifier.height(Spacing.l))
                        val label = plan?.version?.attributes?.playLabel(stringResource(R.string.language_untagged)) ?: stringResource(R.string.play_nothing)
                        // Continue, Restart and Remove are wider than the text column, so they may run past it.
                        Row(
                            if (resumable) Modifier.wrapContentWidth(Alignment.Start, unbounded = true) else Modifier,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NuageButton(
                                if (resumable) stringResource(R.string.play_continue, clock(plan!!.from)) else stringResource(R.string.play),
                                onClick = { plan?.let(onPlay) },
                                modifier = Modifier.focusRequester(play),
                                icon = Icons.Filled.PlayArrow,
                                enabled = plan != null,
                            )
                            if (resumable) {
                                NuageButton(stringResource(R.string.play_restart), onClick = { plan?.let { onPlay(it.fromStart()) } }, icon = Icons.Filled.Refresh)
                                NuageButton(stringResource(R.string.continue_watching_remove), onClick = onRemove)
                            } else {
                                PlayLabel(label)
                            }
                        }
                        if (resumable) {
                            Spacer(Modifier.height(Spacing.s))
                            PlayLabel(label)
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
        if (isShow) episodes(context, episodes, nextEpisode, onEpisode)
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
 * first, with how far the Viewer got in it. Choosing one plays its Best playable Version; one not
 * aired yet says when it will be instead, and plays nothing. [nextEpisode] marks [ShowEpisodes.next].
 *
 * The items here are counted by [previousEpisodeIndex]: keep the two in step.
 */
private fun LazyListScope.episodes(context: Context, episodes: ShowEpisodes?, nextEpisode: FocusRequester, onEpisode: (Episode) -> Unit) {
    item(key = "episodes") {
        Text(stringResource(R.string.episodes_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp))
    }
    val note = episodes.note(context)
    if (note != null) item(key = "episodes note") { Text(note, style = Type.caption, color = Colors.inkMuted) }
    episodes?.seasons.orEmpty().forEach { season ->
        item(key = "season ${season.number}") {
            Text(
                "${if (season.number == 0) context.getString(R.string.season_specials) else context.getString(R.string.season_title, season.number)} · " +
                    context.resources.getQuantityString(R.plurals.episodes_count, season.episodes.size, season.episodes.size),
                style = Type.title,
                color = Colors.ink,
                modifier = Modifier.padding(top = Spacing.m, bottom = 2.dp),
            )
        }
        season.episodes.forEach { episode ->
            item(key = "episode ${season.number} ${episode.number}") {
                val available = episode.availableOn
                SettingRow(
                    episode.name?.let { context.getString(R.string.episode_named, episode.number, it) } ?: context.getString(R.string.episode_number, episode.number),
                    if (available != null) context.getString(R.string.episode_available, context.monthDay(available))
                    else (episode.languages + episode.otherLanguages).joinToString(", ") { group -> group.language?.let(::languageName) ?: context.getString(R.string.language_untagged) },
                    onClick = { onEpisode(episode) },
                    modifier = if (episode.id == episodes?.next) Modifier.focusRequester(nextEpisode) else Modifier,
                    progress = episode.progress,
                    enabled = available == null,
                )
            }
        }
    }
}

/** What to say above a Show's Episodes while they're read, or when some or all can't be. */
private fun ShowEpisodes?.note(context: Context): String? = when {
    this == null -> context.getString(R.string.episodes_reading)
    seasons.isEmpty() && unreadVersions > 0 -> context.getString(R.string.episodes_unreachable)
    seasons.isEmpty() -> context.getString(R.string.episodes_none)
    unreadVersions > 0 -> context.resources.getQuantityString(R.plurals.episodes_unread, unreadVersions, unreadVersions)
    else -> null
}

/**
 * The list index of the Episode row just before [next] (in an earlier season, when it opens one),
 * null when [next] is the first: the header, the Episodes headline and its note come first, then each
 * season's heading and its Episodes, as [episodes] lays them out.
 */
private fun ShowEpisodes.previousEpisodeIndex(next: EpisodeNumber): Int? {
    // The header and the Episodes headline, then the note when there is one.
    var index = 2 + (if (seasons.isEmpty() || unreadVersions > 0) 1 else 0)
    var previous: Int? = null
    for (season in seasons) {
        index++
        for (episode in season.episodes) {
            if (episode.id == next) return previous
            previous = index++
        }
    }
    return null
}

/** "October 31" in the App language, with the year when it isn't this one. */
private fun Context.monthDay(date: LocalDate): String {
    val locale = resources.configuration.locales[0]
    val skeleton = if (date.year == LocalDate.now().year) "MMMMd" else "MMMMdyyyy"
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/** How far the Episode before the next one is scrolled up: through the top margin, to the screen's edge. */
private val PEEK = Spacing.screenY

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
