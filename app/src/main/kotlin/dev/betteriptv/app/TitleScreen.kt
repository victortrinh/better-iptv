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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.betteriptv.app.ui.components.TitleName
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import dev.betteriptv.app.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Title's details: what it is, a list of actions led by Play with the Version it will start, and
 * every Version grouped by language, the Viewer's languages first and the rest behind "Other
 * languages". A Show lists its seasons and Episodes first, each Episode with the languages it's
 * available in and how far the Viewer got, once the Provider's show details are read, and choosing
 * one plays it. Episodes TMDB lists as not aired yet say when they will be, and can't be played.
 * TMDB's backdrop fills the screen behind them. Without one, the poster sits beside the header
 * instead: a poster blown up as a backdrop is too blurry and too bright to keep text on it readable.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead. On a Movie the Viewer stopped in,
 * Play becomes "Continue from 1:02:13", with Restart and "Remove from Continue watching" under it.
 * A Show's Play plays the Episode to watch next, naming it ("Continue S1 E6", or "Play S1 E1" when
 * not started), and the header names that Episode. Down from the actions goes to that Episode in
 * the list, the one before it peeking above; with nothing to play, focus goes there.
 *
 * The page arrives whole ([TitlePage]): opened from a card grown into its textless backdrop
 * ([openedOn]), it opens on that picture and gathers the details, the plan and a Show's Episodes
 * before its content fades in.
 */
@Composable
fun TitleScreen(
    library: Library,
    id: TitleId,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    qualityCap: QualityCap,
    deviceProfile: () -> DeviceProfile,
    onPlay: (PlayPlan, String) -> Unit,
    openedOn: String? = null,
) {
    // Each Header read that Confirms something reloads the details and the plan, in place.
    var reads by remember(id) { mutableIntStateOf(0) }
    // Removing it from Continue watching reloads the plan too, which then starts from the beginning.
    var removals by remember(id) { mutableIntStateOf(0) }
    // The plan comes with the details, so first focus lands once, on Play or on the first Version.
    val loaded by produceState<Pair<TitleDetails, PlayPlan?>?>(null, id, contentLanguages, appLanguage, region, qualityCap, reads, removals) {
        val details = library.title(id, contentLanguages, appLanguage, region) ?: return@produceState
        value = details to library.playPlan(id, contentLanguages, withContext(Dispatchers.Default) { deviceProfile() }, qualityCap)
    }
    // A Show's Play plays the Episode to watch next, so it comes with the Episodes.
    val show by produceState<Pair<ShowEpisodes, PlayPlan?>?>(null, id, contentLanguages, appLanguage, qualityCap, reads) {
        if (id.kind != TitleKind.Show) return@produceState
        val episodes = library.episodes(id, contentLanguages, appLanguage) ?: return@produceState
        val device = withContext(Dispatchers.Default) { deviceProfile() }
        value = episodes to episodes.next?.let { library.episodePlayPlan(id, it, contentLanguages, device, qualityCap) }
    }
    // A Show's reads need its show details, so they start once its Episodes are in.
    val readable = id.kind == TitleKind.Movie || show != null
    LaunchedEffect(id, contentLanguages, readable) { if (readable) library.readHeaders(id, contentLanguages) { reads++ } }
    val scope = rememberCoroutineScope()
    TitlePage(
        id, openedOn, loaded, show,
        onPlay = onPlay,
        onRemove = { scope.launch { library.removeFromContinueWatching(id); removals++ } },
        onChoose = { version, name ->
            scope.launch { library.chosenPlayPlan(id, version.listingId, contentLanguages)?.let { onPlay(it, name) } }
        },
        onEpisode = { episode, name ->
            scope.launch {
                val device = withContext(Dispatchers.Default) { deviceProfile() }
                library.episodePlayPlan(id, EpisodeNumber(episode.season, episode.number), contentLanguages, device, qualityCap)?.let { onPlay(it, name) }
            }
        },
    )
}

/**
 * [TitleScreen]'s page, with what it reads from the Library stood in: the details and their plan
 * ([loaded]), and on a Show its Episodes and their plan ([show]), each null until read. Screen tests
 * drive it.
 *
 * It arrives whole (DESIGN.md, Motion). Opened from a card that had grown into its textless
 * backdrop ([openedOn]), its first frame is that card's copy of the picture, from memory, and the
 * screen-sized one fades in over it once read: the same picture, so the swap can't be seen. The
 * header and actions wait for the details, the plan and a Show's Episodes, up to
 * [Motion.pageArrivalMillis], then fade in together. A Show whose Episodes take longer shows Play
 * disabled, with focus on it, and Play enables where it is once they're read.
 */
@Composable
internal fun TitlePage(
    id: TitleId,
    openedOn: String?,
    loaded: Pair<TitleDetails, PlayPlan?>?,
    show: Pair<ShowEpisodes, PlayPlan?>?,
    onPlay: (PlayPlan, String) -> Unit,
    onRemove: () -> Unit = {},
    onChoose: (Version, String) -> Unit = { _, _ -> },
    onEpisode: (Episode, String) -> Unit = { _, _ -> },
) {
    val title = loaded?.first
    var waited by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        delay(Motion.pageArrivalMillis.toLong())
        waited = true
    }
    // Neither the details nor the Episodes go back to unread once read, so once arrived it stays.
    val arrived = title != null && (id.kind != TitleKind.Show || show != null || waited)
    val fade = remember(id) { Animatable(0f) }
    LaunchedEffect(arrived) { if (arrived) fade.animateTo(1f, tween(Motion.screenFadeMillis)) }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        val first = rememberArtworkInMemory(openedOn)
        val backdrop = rememberArtwork(title?.backdrop)
        if (backdrop != null) Backdrop(backdrop, under = first) else if (first != null) Backdrop(first)
        if (arrived) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                TitleContent(
                    title!!, loaded.second ?: show?.second, show?.first,
                    hasBackdrop = backdrop != null || first != null,
                    onPlay = { onPlay(it, title.name) },
                    onRemove = onRemove,
                    onChoose = { onChoose(it, title.name) },
                    onEpisode = { onEpisode(it, title.name) },
                )
            }
        }
    }
}

@Composable
private fun TitleContent(
    title: TitleDetails,
    plan: PlayPlan?,
    episodes: ShowEpisodes?,
    hasBackdrop: Boolean,
    onPlay: (PlayPlan) -> Unit,
    onRemove: () -> Unit,
    onChoose: (Version) -> Unit,
    onEpisode: (Episode) -> Unit,
) {
    val context = LocalContext.current
    val image = rememberArtwork(title.image.takeIf { !hasBackdrop })
    val logo = rememberArtwork(title.logo)
    var showOthers by rememberSaveable(title.id) { mutableStateOf(false) }
    val firstVersion = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val nextEpisode = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val isShow = title.id.kind == TitleKind.Show
    // Play takes first focus. On a Movie with nothing to start, the first Version takes it. A Show's
    // Play is there from the first frame, disabled while its Episodes are read, and enables where it
    // is once they are, so focus never jumps to it; with no Episode to play, the next Episode takes
    // focus then, unless the Viewer has moved on.
    var firstVersionFocused by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(title.id) {
        (if (plan != null || isShow) play else firstVersion).requestFocus()
    }
    // Removing it from Continue watching takes its row away, so focus goes back to Play.
    val resumable = plan != null && plan.from > 0
    var wasResumable by remember(title.id) { mutableStateOf(resumable) }
    LaunchedEffect(resumable) {
        if (wasResumable && !resumable) play.requestFocus()
        wasResumable = resumable
    }
    val next = episodes?.next
    val peekPx = with(LocalDensity.current) { PEEK.roundToPx() }
    val scope = rememberCoroutineScope()
    // The Episode before the next one peeks above it, so the Viewer sees where they are in the season.
    // A list opens here once; from then on EdgeScroll moves it.
    suspend fun focusNextEpisode(fromActions: Boolean = false) {
        if (episodes == null || next == null) return
        val previous = episodes.previousEpisodeIndex(next)
        // From the actions, a first Episode may not be laid out yet: its headline comes to the top instead.
        if (previous != null) listState.scrollToItem(previous, peekPx) else if (fromActions) listState.scrollToItem(1)
        withFrameNanos {}
        nextEpisode.requestFocus()
    }
    LaunchedEffect(title.id, episodes != null, next, plan != null) {
        if (episodes == null || (listHasFocus && !playFocused && !firstVersionFocused)) return@LaunchedEffect
        if (plan != null) {
            withFrameNanos {}
            play.requestFocus()
        } else {
            focusNextEpisode()
        }
    }
    val episode = next?.let { number -> episodes.seasons.firstOrNull { it.number == number.season }?.episodes?.firstOrNull { it.id == number } }
    // Down from the last action goes to the next Episode, rather than the first one on screen.
    val toNextEpisode = Modifier.onPreviewKeyEvent { event ->
        if (next == null || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) scope.launch { focusNextEpisode(fromActions = true) }
        true
    }
    // With no Version in the Viewer's languages, the other languages are all there is to show.
    val shown = title.languages.ifEmpty { title.otherLanguages }
    val best = shown.firstOrNull()?.versions?.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize().onFocusChanged { listHasFocus = it.hasFocus },
        state = listState,
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            Row(Modifier.padding(top = Spacing.xl, bottom = Spacing.xl)) {
                Column(Modifier.width(470.dp)) {
                    TitleName(title.name, logo)
                    Spacer(Modifier.height(Spacing.s))
                    // The badges go under the line when both don't fit, so a long rank badge ("#1 in movies in
                    // United States") doesn't cut the genres and Version count off (#208).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        val versions = (title.languages + title.otherLanguages).sumOf { it.versionCount }
                        val count = pluralStringResource(R.plurals.versions_count, versions, versions)
                        Text(
                            listOfNotNull(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), title.genres.take(2).joinToString(", ").ifEmpty { null }, count)
                                .joinToString(" · "),
                            style = Type.body,
                            color = Colors.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BadgeRow(listOfNotNull(title.rank?.badge()) + best?.attributes?.cardBadges().orEmpty())
                    }
                    // A Show's header names the Episode its Play starts; its line is kept while the Episodes are read.
                    if (isShow && (plan?.episode != null || episodes == null)) {
                        val number = plan?.episode
                        Text(
                            when {
                                number == null -> ""
                                episode?.name != null -> stringResource(R.string.details_episode_named, number.season, number.number, episode.name!!)
                                else -> stringResource(R.string.details_episode, number.season, number.number)
                            },
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
                    // Play is there from the first frame, on a Show too, where it waits disabled for the Episodes.
                    Spacer(Modifier.height(Spacing.l))
                    val number = plan?.episode
                    // Having played any of the Show, the Viewer continues it.
                    val started = episodes?.seasons.orEmpty().any { season -> season.episodes.any { it.progress != null } }
                    val text = when {
                        number != null && (started || resumable) -> stringResource(R.string.play_continue_episode, number.season, number.number)
                        number != null -> stringResource(R.string.play_episode, number.season, number.number)
                        resumable -> stringResource(R.string.play_continue, clock(plan!!.from))
                        else -> stringResource(R.string.play)
                    }
                    val showRemove = resumable && !isShow
                    Column(Modifier.width(ACTIONS_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PrimaryAction(
                            text,
                            Icons.Filled.PlayArrow,
                            onClick = { plan?.let(onPlay) },
                            modifier = Modifier.testTag("play").focusRequester(play).onFocusChanged { playFocused = it.isFocused }.then(if (resumable) Modifier else toNextEpisode),
                            progress = plan?.progress,
                            enabled = plan != null,
                        )
                        if (resumable) {
                            ActionRow(
                                stringResource(R.string.play_restart),
                                Icons.Filled.Refresh,
                                onClick = { plan?.let { onPlay(it.fromStart()) } },
                                modifier = if (showRemove) Modifier else toNextEpisode,
                            )
                            // Continue watching holds Movies: an Episode's place is kept with the Show.
                            if (showRemove) ActionRow(stringResource(R.string.continue_watching_remove), Icons.Filled.Close, onClick = onRemove)
                        }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    // While a Show's Episodes are read, the label's line waits empty.
                    PlayLabel(
                        plan?.version?.attributes?.playLabel(stringResource(R.string.language_untagged))
                            ?: if (isShow && episodes == null) "" else stringResource(R.string.play_nothing),
                    )
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
        // Its Backups follow it, so the Viewer can pick one that looks the same from its name but not on screen.
        version.backups.forEach { backup ->
            item(key = backup.listingId) { VersionRow(context.versionLine(backup, backup = true), onClick = { onChoose(backup) }) }
        }
    }
}
