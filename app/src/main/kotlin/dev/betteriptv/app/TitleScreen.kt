package dev.betteriptv.app

import android.content.Context
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.Backdrop
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageIcons
import dev.betteriptv.app.ui.components.ActionRow
import dev.betteriptv.app.ui.components.PrimaryAction
import dev.betteriptv.app.ui.components.TitleName
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Library
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.EpisodeStill
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
 * A Title's page, in two levels (DESIGN.md, Details). The page itself holds the Title and what to do
 * with it: the logo, the metadata line with its badges, two lines of description and the actions,
 * Play first, then Restart, "Remove from Continue watching" and Versions, whose value says what Play
 * starts ("English · 1080p · 9 Versions"). A Movie's fits on one screen. OK on Versions opens the
 * [section] [TitleSection.Versions] ([onSection]): the Versions screen, which lists every Version
 * grouped by language over the same backdrop ([VersionsContent]); on a Show, those of the Episode
 * Play starts. The caller closes it on Back and reopens the page with [returnedFrom] set, so focus
 * goes back to the action that opened it.
 *
 * On a Show, an Episodes action after Play says where the Viewer is ("Season 2 · E4"), and OK on it
 * opens [TitleSection.Episodes], the Episode picker ([EpisodesContent]): seasons on the left, the
 * picked season's Episodes on the right, opening on the next Episode to watch; choosing one plays it.
 * TMDB's backdrop fills the screen. Without one, the poster sits beside the header instead: a
 * poster blown up as a backdrop is too blurry and too bright to keep text on it readable.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead. On a Movie the Viewer stopped in,
 * Play becomes "Continue from 1:02:13", with Restart and "Remove from Continue watching" under it.
 * Trailer, before Versions, plays the Title's trailer in YouTube's player inside Zugig ([TrailerContent],
 * ADR 0012), and is left out when it has none.
 * A Show's Play plays the Episode to watch next, naming it ("Continue S1 E6", or "Play S1 E1" when
 * not started), and the header names that Episode; with nothing to play, focus goes to the Episodes action.
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
    onPlay: (PlayPlan, String, StartingArt) -> Unit,
    preferOriginalAudio: Boolean = true,
    openedOn: String? = null,
    section: TitleSection? = null,
    returnedFrom: TitleSection? = null,
    onSection: (TitleSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
) {
    // Each Header read that Confirms something reloads the details and the plan, in place.
    var reads by remember(id) { mutableIntStateOf(0) }
    // Removing it from Continue watching reloads the plan too, which then starts from the beginning.
    var removals by remember(id) { mutableIntStateOf(0) }
    // The plan comes with the details, so first focus lands once, on Play or on the first Version.
    val loaded by produceState<Pair<TitleDetails, PlayPlan?>?>(null, id, contentLanguages, preferOriginalAudio, appLanguage, region, qualityCap, reads, removals) {
        val details = library.title(id, contentLanguages, appLanguage, region, preferOriginalAudio) ?: return@produceState
        value = details to library.playPlan(id, contentLanguages, withContext(Dispatchers.Default) { deviceProfile() }, qualityCap, preferOriginalAudio)
    }
    // A Show's Play plays the Episode to watch next, so it comes with the Episodes.
    val show by produceState<Pair<ShowEpisodes, PlayPlan?>?>(null, id, contentLanguages, preferOriginalAudio, appLanguage, qualityCap, reads) {
        if (id.kind != TitleKind.Show) return@produceState
        val episodes = library.episodes(id, contentLanguages, appLanguage, preferOriginalAudio) ?: return@produceState
        val device = withContext(Dispatchers.Default) { deviceProfile() }
        value = episodes to episodes.next?.let { library.episodePlayPlan(id, it, contentLanguages, device, qualityCap, preferOriginalAudio) }
    }
    // A Show's reads need its show details, so they start once its Episodes are in.
    val readable = id.kind == TitleKind.Movie || show != null
    LaunchedEffect(id, contentLanguages, preferOriginalAudio, readable) { if (readable) library.readHeaders(id, contentLanguages, preferOriginalAudio) { reads++ } }
    val scope = rememberCoroutineScope()
    // What the player shows while it starts: this page's own backdrop, already in memory.
    val start = { plan: PlayPlan, name: String -> onPlay(plan, name, loaded?.first.let { StartingArt.Title(it?.backdrop, it?.logo) }) }
    // Play lets the Header read in flight finish and plays what that settles, from where this plan starts (Restart's start included).
    val play = { plan: PlayPlan, name: String ->
        scope.launch {
            val device = withContext(Dispatchers.Default) { deviceProfile() }
            val settled = library.pressPlay(id, contentLanguages, device, qualityCap, preferOriginalAudio, plan.episode) ?: plan
            start(settled.copy(from = plan.from, length = plan.length), name)
        }
        Unit
    }
    TitlePage(
        id, openedOn, loaded, show,
        onPlay = play,
        contentLanguages = contentLanguages,
        section = section,
        returnedFrom = returnedFrom,
        onSection = onSection,
        onCloseSection = onCloseSection,
        onRemove = { scope.launch { library.removeFromContinueWatching(id); removals++ } },
        onChoose = { version, name ->
            scope.launch {
                // A Show's Versions are those of the Episode its Play starts (versionsOf), else of its next Episode.
                val episode = show?.second?.episode ?: show?.first?.next
                val plan = if (id.kind == TitleKind.Show) episode?.let { library.chosenEpisodePlayPlan(id, it, version.listingId, contentLanguages, preferOriginalAudio) }
                else library.chosenPlayPlan(id, version.listingId, contentLanguages, preferOriginalAudio)
                plan?.let { start(it, name) }
            }
        },
        stillsOf = { season -> library.seasonStills(id, season, appLanguage) },
        onEpisode = { episode, name ->
            scope.launch {
                val device = withContext(Dispatchers.Default) { deviceProfile() }
                library.pressPlay(id, contentLanguages, device, qualityCap, preferOriginalAudio, EpisodeNumber(episode.season, episode.number))?.let { start(it, name) }
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
 *
 * With a [section] open, the page gives way to that screen over the same backdrop, and coming back
 * ([returnedFrom]) puts focus on the action that opened it.
 */
@Composable
internal fun TitlePage(
    id: TitleId,
    openedOn: String?,
    loaded: Pair<TitleDetails, PlayPlan?>?,
    show: Pair<ShowEpisodes, PlayPlan?>?,
    onPlay: (PlayPlan, String) -> Unit,
    section: TitleSection? = null,
    returnedFrom: TitleSection? = null,
    onSection: (TitleSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
    onRemove: () -> Unit = {},
    onChoose: (Version, String) -> Unit = { _, _ -> },
    onEpisode: (Episode, String) -> Unit = { _, _ -> },
    stillsOf: suspend (Int) -> Map<Int, EpisodeStill> = { emptyMap() },
    contentLanguages: List<String> = emptyList(),
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
            val plan = loaded.second ?: show?.second
            val versions = versionsOf(title!!, plan, show?.first, contentLanguages)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                when (section) {
                    TitleSection.Versions -> if (versions != null) VersionsContent(versions, title.cast, title.fromTmdb, onChoose = { onChoose(it, title.name) })
                    TitleSection.Trailer -> title.trailer?.let { TrailerContent(it, title.name, StartingArt.Title(title.backdrop, title.logo), onClose = onCloseSection) }
                    TitleSection.Episodes -> show?.first?.let { EpisodesContent(title.name, it, onEpisode = { episode -> onEpisode(episode, title.name) }, stillsOf = stillsOf) }
                    null -> TitleContent(
                        title, plan, show?.first,
                        versions = versions,
                        hasBackdrop = backdrop != null || first != null,
                        returnedFrom = returnedFrom,
                        onPlay = { onPlay(it, title.name) },
                        onRemove = onRemove,
                        onSection = onSection,
                    )
                }
            }
        }
    }
}

/**
 * A screen a Title page's action opens over the Title's backdrop, the page's second level: the
 * Versions screen, a Show's Episode picker, or the Trailer screen. Back returns to the page, with focus
 * on the action that opened it, as does the Trailer screen once its trailer ends ([TitlePage]'s onCloseSection).
 */
enum class TitleSection { Versions, Episodes, Trailer }

/**
 * What [title]'s Versions screen lists: a Movie's Versions, or on a Show those of the Episode [plan]
 * starts, named after it, else of its next Episode ([ShowEpisodes.next]). Null on a Show whose
 * Episodes aren't read yet, or that has none to play.
 */
@Composable
private fun versionsOf(title: TitleDetails, plan: PlayPlan?, episodes: ShowEpisodes?, contentLanguages: List<String>): VersionsList? {
    // The details name the original language only when Prefer original audio acts on the Title.
    val original = title.originalLanguage
    if (title.id.kind != TitleKind.Show) return VersionsList(title.name, title.languages, title.otherLanguages, contentLanguages, original)
    val number = plan?.episode ?: episodes?.next ?: return null
    val episode = episodes?.seasons?.firstOrNull { it.number == number.season }?.episodes?.firstOrNull { it.id == number } ?: return null
    val named = episode.name?.let { stringResource(R.string.details_episode_named, number.season, number.number, it) }
        ?: stringResource(R.string.details_episode, number.season, number.number)
    return VersionsList("${title.name} · $named", episode.languages, episode.otherLanguages, contentLanguages, original)
}

@Composable
private fun TitleContent(
    title: TitleDetails,
    plan: PlayPlan?,
    episodes: ShowEpisodes?,
    versions: VersionsList?,
    hasBackdrop: Boolean,
    returnedFrom: TitleSection?,
    onPlay: (PlayPlan) -> Unit,
    onRemove: () -> Unit,
    onSection: (TitleSection) -> Unit,
) {
    val context = LocalContext.current
    val image = rememberArtwork(title.image.takeIf { !hasBackdrop })
    val logo = rememberArtwork(title.logo)
    val play = remember { FocusRequester() }
    val versionsAction = remember { FocusRequester() }
    val episodesAction = remember { FocusRequester() }
    val trailerAction = remember { FocusRequester() }
    val isShow = title.id.kind == TitleKind.Show
    val next = episodes?.next
    // A Show's Episodes action is there from the first frame, disabled while they're read, and left out of a Show with none.
    val showEpisodes = isShow && (episodes == null || next != null)
    // Play takes first focus. On a Movie with nothing to start, Versions takes it, the way to see why.
    // A Show's Play is there from the first frame, disabled while its Episodes are read, and enables
    // where it is once they are, so focus never jumps to it; with no Episode to play, the Episodes
    // action takes focus then, unless the Viewer has moved on. Back from a section lands on its action.
    var playFocused by remember { mutableStateOf(false) }
    LaunchedEffect(title.id) {
        when {
            returnedFrom == TitleSection.Versions && versions != null -> versionsAction
            returnedFrom == TitleSection.Episodes && next != null -> episodesAction
            returnedFrom == TitleSection.Trailer && title.trailer != null -> trailerAction
            plan != null || isShow -> play
            else -> versionsAction
        }.requestFocus()
    }
    // Removing it from Continue watching takes its row away, so focus goes back to Play.
    val resumable = plan != null && plan.from > 0
    var wasResumable by remember(title.id) { mutableStateOf(resumable) }
    LaunchedEffect(resumable) {
        if (wasResumable && !resumable) play.requestFocus()
        wasResumable = resumable
    }
    // Coming back from a section, the Episodes were already read: focus stays on its action.
    LaunchedEffect(title.id, episodes != null, next, plan != null) {
        if (returnedFrom != null || episodes == null || plan != null || next == null || !playFocused) return@LaunchedEffect
        withFrameNanos {}
        episodesAction.requestFocus()
    }
    val episode = next?.let { number -> episodes.seasons.firstOrNull { it.number == number.season }?.episodes?.firstOrNull { it.id == number } }
    // With no Version in the Viewer's languages, the other languages are all there is to show.
    val best = title.languages.ifEmpty { title.otherLanguages }.firstOrNull()?.versions?.firstOrNull()
    // A Show's Versions action waits, disabled, while its Episodes are read, so the list doesn't grow under focus.
    val showVersions = versions != null || (isShow && episodes == null)

    Row(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY).padding(top = Spacing.xl, bottom = Spacing.xl)) {
        Column(Modifier.width(470.dp)) {
            TitleName(title.name, logo)
            Spacer(Modifier.height(Spacing.s))
            // The badges go under the line when both don't fit, so a long rank badge ("#1 in movies in
            // United States") doesn't cut the genres off (#208).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // The Version count is the Versions action's.
                Text(
                    listOfNotNull(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), title.genres.take(2).joinToString(", ").ifEmpty { null })
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
                        episode?.name != null && episode.id == number -> stringResource(R.string.details_episode_named, number.season, number.number, episode.name!!)
                        else -> stringResource(R.string.details_episode, number.season, number.number)
                    },
                    style = Type.title,
                    color = Colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
            // Two lines, so the page fits on one screen; the cast is on the Versions screen.
            title.description?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(it, style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    modifier = Modifier.testTag("play").focusRequester(play).onFocusChanged { playFocused = it.isFocused },
                    progress = plan?.progress,
                    enabled = plan != null,
                )
                if (resumable) {
                    ActionRow(stringResource(R.string.play_restart), Icons.Filled.Refresh, onClick = { plan?.let { onPlay(it.fromStart()) } })
                    // Continue watching holds Movies: an Episode's place is kept with the Show.
                    if (showRemove) ActionRow(stringResource(R.string.continue_watching_remove), Icons.Filled.Close, onClick = onRemove)
                }
                if (showEpisodes) {
                    // Its value says where the Viewer is in the Show ("Season 2 · E4").
                    ActionRow(
                        stringResource(R.string.episodes_title),
                        Icons.Filled.Menu,
                        onClick = { onSection(TitleSection.Episodes) },
                        modifier = Modifier.testTag("episodes action").focusRequester(episodesAction),
                        value = episodes?.let { context.episodesValue(it) } ?: "",
                        enabled = next != null,
                    )
                }
                // Plays inside Zugig, so Back comes back here (ADR 0012); left out when the Title has no trailer.
                if (title.trailer != null) {
                    ActionRow(
                        stringResource(R.string.title_trailer),
                        NuageIcons.Movie,
                        onClick = { onSection(TitleSection.Trailer) },
                        modifier = Modifier.testTag("trailer action").focusRequester(trailerAction),
                    )
                }
                if (showVersions) {
                    // Its value says what Play starts, and how many Versions there are to choose from instead.
                    ActionRow(
                        stringResource(R.string.versions_title),
                        Icons.AutoMirrored.Filled.List,
                        onClick = { onSection(TitleSection.Versions) },
                        modifier = Modifier.testTag("versions action").focusRequester(versionsAction),
                        value = versions?.let { context.versionsValue(plan?.version, it.count) } ?: "",
                        enabled = versions != null,
                    )
                }
            }
            // Nothing to play says why under the actions; once a Show's Episodes are read, as their wait is no reason.
            if (plan == null && !(isShow && episodes == null)) {
                Spacer(Modifier.height(Spacing.s))
                PlayLabel(episodes.note(context) ?: stringResource(R.string.play_nothing))
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

/** Why a Show whose Episodes are read has none to offer, or null when it has some. */
private fun ShowEpisodes?.note(context: Context): String? = when {
    this == null || seasons.isNotEmpty() -> null
    unreadVersions > 0 -> context.getString(R.string.episodes_unreachable)
    else -> context.getString(R.string.episodes_none)
}

/** The action list's width: rows of one width, sized for "Remove from Continue watching" and a progress bar beside "Reprendre à 1:02:13". */
private val ACTIONS_WIDTH = 360.dp

/** The muted line under the actions saying why Play is disabled. */
@Composable
private fun PlayLabel(text: String) {
    Text(text, style = Type.label, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** `2 h 55 min`, or `48 min` under an hour. */
internal fun Context.runtime(minutes: Int): String =
    if (minutes < 60) getString(R.string.runtime_minutes, minutes) else getString(R.string.runtime_hours, minutes / 60, minutes % 60)
