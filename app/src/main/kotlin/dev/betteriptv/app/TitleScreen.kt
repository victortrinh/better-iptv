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
import dev.betteriptv.app.ui.theme.inLayoutDirection
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Library
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.EpisodeStart
import dev.betteriptv.core.NothingToPlay
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.TitleAction
import dev.betteriptv.core.TitlePageContent
import dev.betteriptv.core.TitlePageScreen
import dev.betteriptv.core.TitleVersions
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.betteriptv.app.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Title's page, in two levels (DESIGN.md, Details), on the bare ground. The page itself holds the
 * Title and what to do with it: its name in Display type ([TitleName]), the metadata line (year ·
 * runtime · genres · rating, as the Provider gives them, ADR 0014) with its badges, two lines of the
 * Provider's plot and the actions,
 * Play first, then Restart, "Remove from Continue watching" and Versions, whose value says what Play
 * starts ("English · 1080p · 9 Versions"). A Movie's fits on one screen. OK on Versions opens the
 * [section] [TitleSection.Versions] ([onSection]): the Versions screen, which lists every Version
 * grouped by language ([VersionsContent]); on a Show, those of the Episode
 * Play starts. The caller closes it on Back and reopens the page with [returnedFrom] set, so focus
 * goes back to the action that opened it.
 *
 * On a Show, an Episodes action after Play says where the Viewer is ("Season 2 · E4"), and OK on it
 * opens [TitleSection.Episodes], the Episode picker ([EpisodesContent]): seasons on the left, the
 * picked season's Episodes on the right with the Provider's stills, opening on the next Episode to
 * watch; choosing one plays it.
 * The Title's poster sits at the right of the header (ADR 0018): the one its card shows, at the same
 * size, so it is already in memory when the page opens from a card or the hero.
 *
 * Play starts the Best playable Version for this stick and TV ([deviceProfile] is read when the
 * screen opens); choosing a Version row plays that one instead. On a Movie the Viewer stopped in,
 * Play becomes "Continue from 1:02:13", with Restart and "Remove from Continue watching" under it.
 * Trailer, before Versions, plays the Title's trailer in YouTube's player inside Zugig ([TrailerContent],
 * ADR 0012), and is left out when the Provider gives no trailer key.
 * A Show's Play plays the Episode to watch next, naming it ("Continue S1 E6", or "Play S1 E1" when
 * not started), and the header names that Episode; with nothing to play, focus goes to the Episodes action.
 *
 * The page arrives whole ([TitlePage]): its content fades in once it has the details, the plan and a
 * Show's Episodes.
 *
 * What is on the page and behind its actions is the Core's ([TitlePageScreen]): which actions there
 * are and what each starts, the Version the badges tell of, the Versions and the Episodes. This
 * screen asks for it while it shows, words it and draws it.
 */
@Composable
fun TitleScreen(
    library: Library,
    id: TitleId,
    contentLanguages: List<String>,
    appLanguage: String,
    qualityCap: QualityCap,
    deviceProfile: () -> DeviceProfile,
    onPlay: (PlayPlan, String, StartingArt) -> Unit,
    preferOriginalAudio: Boolean = true,
    section: TitleSection? = null,
    returnedFrom: TitleSection? = null,
    onSection: (TitleSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
) {
    val readDevice by rememberUpdatedState(deviceProfile)
    val screen = remember(id, contentLanguages, appLanguage, qualityCap, preferOriginalAudio) {
        TitlePageScreen(id, contentLanguages, appLanguage, qualityCap, preferOriginalAudio) { readDevice() }
    }
    // The page as the Core hands it, and again each time it changes: a Header read that Confirms
    // something, playback that stopped, the Title taken out of Continue watching.
    var content by remember(library, screen) { mutableStateOf<TitlePageContent?>(null) }
    // Only while the app shows: an open page has its Versions read, and a Show's reads can take
    // minutes, each holding the Viewer's one stream (#503).
    val onScreen = rememberOnScreen()
    LaunchedEffect(library, screen) {
        onScreen.collectLatest { on ->
            if (!on) return@collectLatest
            library.screen(screen).collect { handed ->
                // Asked again on coming back to the app, the page it has stands while a Show's Episodes are read again.
                if (!(handed.waitingForEpisodes && content?.waitingForEpisodes == false)) content = handed
            }
        }
    }
    val scope = rememberCoroutineScope()
    // What the player shows while it starts: this page's own poster, already in memory.
    val start = { plan: PlayPlan, name: String -> onPlay(plan, name, StartingArt.Title(content?.title?.posters.orEmpty())) }
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
        id, content,
        onPlay = play,
        section = section,
        returnedFrom = returnedFrom,
        onSection = onSection,
        onCloseSection = onCloseSection,
        onRemove = { scope.launch { library.removeFromContinueWatching(id) } },
        onChoose = { version, name ->
            scope.launch {
                // A Show's Versions are those of an Episode, which the Versions screen's content names.
                val plan = if (id.kind == TitleKind.Show) content?.versions?.episode?.let { library.chosenEpisodePlayPlan(id, it, version.listingId, contentLanguages, preferOriginalAudio) }
                else library.chosenPlayPlan(id, version.listingId, contentLanguages, preferOriginalAudio)
                plan?.let { start(it, name) }
            }
        },
        onEpisode = { episode, name ->
            scope.launch {
                val device = withContext(Dispatchers.Default) { deviceProfile() }
                library.pressPlay(id, contentLanguages, device, qualityCap, preferOriginalAudio, EpisodeNumber(episode.season, episode.number))?.let { start(it, name) }
            }
        },
    )
}

/**
 * [TitleScreen]'s page, drawn from the [content] the Core hands it ([TitlePageContent]), null until
 * the first is read. Screen tests drive it.
 *
 * It arrives whole (DESIGN.md, Motion): the header and actions wait for the details, the plan and a
 * Show's Episodes, up to [Motion.pageArrivalMillis], then fade in together. Details that take
 * longer (a Provider slow to answer) are waited for with the pulsing dot ([WaitingDot]), centred,
 * which crossfades out as the page fades in. A Show whose Episodes
 * take longer shows Play disabled, with focus on it, and Play enables where it is once they're read.
 *
 * With a [section] open, the page gives way to that screen, and coming back ([returnedFrom]) puts
 * focus on the action that opened it.
 *
 * A Kids profile's page comes with no Trailer and no Versions action: Play picks the Best playable
 * Version, as on any profile.
 *
 * While the Header reads are checking Versions, the page says so under its actions and the Versions
 * screen beside its legend ([CheckingVersions], #457); a Kids page doesn't. Until its Versions are
 * checked ([TitlePageContent.checked]), the page names no quality, neither in its badges, the
 * Versions value nor the Versions screen's rows, so it never shows a guess the reads then correct (#546).
 */
@Composable
internal fun TitlePage(
    id: TitleId,
    content: TitlePageContent?,
    onPlay: (PlayPlan, String) -> Unit,
    section: TitleSection? = null,
    returnedFrom: TitleSection? = null,
    onSection: (TitleSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
    onRemove: () -> Unit = {},
    onChoose: (Version, String) -> Unit = { _, _ -> },
    onEpisode: (Episode, String) -> Unit = { _, _ -> },
) {
    var waited by remember(id) { mutableStateOf(false) }
    // Details still to come after the page arrival time (a Provider slow to answer) are waited for
    // with the dot, centred, which fades in and then crossfades out as the page fades in, as on the
    // rows screens (DESIGN.md, Motion). A page that arrives within the time shows no dot.
    var waiting by remember(id) { mutableStateOf(false) }
    val waitingFade = remember(id) { Fade(0f) }
    val hasDetails by rememberUpdatedState(content != null)
    LaunchedEffect(id) {
        delay(Motion.pageArrivalMillis.toLong())
        waited = true
        if (hasDetails) return@LaunchedEffect
        waiting = true
        waitingFade.fadeIn(Motion.screenFadeMillis)
    }
    // Neither the details nor the Episodes go back to unread once read, so once arrived it stays.
    val arrived = content != null && (!content.waitingForEpisodes || waited)
    val fade = remember(id) { Animatable(0f) }
    LaunchedEffect(arrived) { if (arrived) fade.animateTo(1f, tween(Motion.screenFadeMillis)) }
    val arriving by remember(id) { derivedStateOf { fade.value < 1f } }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        if (waiting && arriving) WaitingDot { waitingFade.value * (1f - fade.value) }
        if (arrived) {
            checkNotNull(content)
            val title = content.title
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                when (section) {
                    TitleSection.Versions -> content.versions?.let { versions ->
                        VersionsContent(versions.listed(title.name), title.cast, title.directors, onChoose = { onChoose(it, title.name) }, checking = content.checking, checked = content.checked)
                    }
                    TitleSection.Trailer -> title.trailer?.let { TrailerContent(it, title.name, StartingArt.Title(title.posters), onClose = onCloseSection) }
                    TitleSection.Episodes -> content.episodes?.let { EpisodesContent(title.name, it, onEpisode = { episode -> onEpisode(episode, title.name) }) }
                    null -> TitleContent(
                        content,
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
 * A screen a Title page's action opens, the page's second level: the Versions screen, a Show's Episode picker, or the Trailer screen. Back returns to the page, with focus
 * on the action that opened it, as does the Trailer screen once its trailer ends ([TitlePage]'s onCloseSection).
 */
enum class TitleSection { Versions, Episodes, Trailer }

/**
 * These Versions as the Versions screen lists them, under what they're Versions of: the Title
 * ([title] is its name), or the Show and its Episode ("Breaking Bad · S1 E6").
 */
@Composable
private fun TitleVersions.listed(title: String): VersionsList {
    val number = episode ?: return VersionsList(title, languages, otherLanguages, contentLanguages, originalLanguage)
    val named = episodeName?.let { stringResource(R.string.details_episode_named, number.season, number.number, it) }
        ?: stringResource(R.string.details_episode, number.season, number.number)
    return VersionsList("$title · $named", languages, otherLanguages, contentLanguages, originalLanguage)
}

@Composable
private fun TitleContent(
    content: TitlePageContent,
    returnedFrom: TitleSection?,
    onPlay: (PlayPlan) -> Unit,
    onRemove: () -> Unit,
    onSection: (TitleSection) -> Unit,
) {
    val context = LocalContext.current
    val title = content.title
    val playing = content.play
    val plan = playing.plan
    val episodes = content.episodes
    // The poster its card and the hero show, at the same size: from memory when they drew it.
    val image = rememberPoster(title.posters)
    val play = remember { FocusRequester() }
    val versionsAction = remember { FocusRequester() }
    val episodesAction = remember { FocusRequester() }
    val trailerAction = remember { FocusRequester() }
    val isShow = title.id.kind == TitleKind.Show
    val next = episodes?.next
    val hasVersionsAction = content.actions.any { it is TitleAction.Versions }
    // Play takes first focus. On a Movie with nothing to start, Versions takes it, the way to see why.
    // A Show's Play is there from the first frame, disabled while its Episodes are read, and enables
    // where it is once they are, so focus never jumps to it; with no Episode to play, the Episodes
    // action takes focus then, unless the Viewer has moved on. Back from a section lands on its action.
    var playFocused by remember { mutableStateOf(false) }
    LaunchedEffect(title.id) {
        when {
            returnedFrom == TitleSection.Versions && content.versions != null -> versionsAction
            returnedFrom == TitleSection.Episodes && next != null -> episodesAction
            returnedFrom == TitleSection.Trailer && title.trailer != null -> trailerAction
            plan != null || isShow || !hasVersionsAction -> play
            else -> versionsAction
        }.requestFocus()
    }
    // Removing it from Continue watching takes its row away, so focus goes back to Play.
    val resumable = content.actions.any { it is TitleAction.Restart }
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

    Row(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY).padding(top = Spacing.xl, bottom = Spacing.xl)) {
        Column(Modifier.width(470.dp)) {
            TitleName(title.name)
            Spacer(Modifier.height(Spacing.s))
            // The badges go under the line when both don't fit, so they don't cut the genres off (#208).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // The Version count is the Versions action's. What the Provider doesn't give is left out (ADR 0014).
                Text(
                    metaLine(title.year?.toString(), title.runtimeMinutes?.let(context::runtime), genresPart(title.genres), title.rating?.let(::ratingLabel)),
                    modifier = Modifier.testTag("metadata line"),
                    style = Type.body.inLayoutDirection(),
                    color = Colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Until the Versions are checked, the guessed badges keep their place unseen, so the actions don't move when the real ones show (#546).
                val badges = content.best?.attributes?.pageBadges().orEmpty()
                BadgeRow(badges, if (content.checked) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
            }
            // A Show's header names the Episode its Play starts; its line is kept while the Episodes are read.
            if (playing is TitleAction.PlayEpisode || content.waitingForEpisodes) {
                Text(
                    when {
                        playing !is TitleAction.PlayEpisode -> ""
                        playing.name != null -> stringResource(R.string.details_episode_named, playing.episode.season, playing.episode.number, playing.name!!)
                        else -> stringResource(R.string.details_episode, playing.episode.season, playing.episode.number)
                    },
                    style = Type.title,
                    color = Colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
            // The Provider's plot, two lines, so the page fits on one screen; the cast and director are on the Versions screen.
            title.description?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(it, style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Spacing.l))
            // The actions are the Core's, in its order; how each is worded and drawn is the page's.
            Column(Modifier.width(ACTIONS_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                content.actions.forEach { action ->
                    when (action) {
                        // Play is there from the first frame, on a Show too, where it waits disabled for the Episodes.
                        is TitleAction.Play -> PrimaryAction(
                            when (action) {
                                is TitleAction.PlayEpisode -> stringResource(
                                    if (action.start == EpisodeStart.First) R.string.play_episode else R.string.play_continue_episode,
                                    action.episode.season, action.episode.number,
                                )
                                is TitleAction.Continue -> stringResource(R.string.play_continue, clock(action.plan.from))
                                is TitleAction.PlayFromStart -> stringResource(R.string.play)
                            },
                            Icons.Filled.PlayArrow,
                            onClick = { action.plan?.let(onPlay) },
                            modifier = Modifier.testTag("play").focusRequester(play).onFocusChanged { playFocused = it.isFocused },
                            progress = action.plan?.progress,
                            enabled = action.plan != null,
                        )
                        is TitleAction.Restart -> ActionRow(stringResource(R.string.play_restart), Icons.Filled.Refresh, onClick = { onPlay(action.plan) })
                        TitleAction.RemoveFromContinueWatching -> ActionRow(stringResource(R.string.continue_watching_remove), Icons.Filled.Close, onClick = onRemove)
                        // Its value says where the Viewer is in the Show ("Season 2 · E4"); disabled while the Episodes are read.
                        is TitleAction.Episodes -> ActionRow(
                            stringResource(R.string.episodes_title),
                            Icons.Filled.Menu,
                            onClick = { onSection(TitleSection.Episodes) },
                            modifier = Modifier.testTag("episodes action").focusRequester(episodesAction),
                            value = action.place?.let { context.episodesValue(it) } ?: "",
                            enabled = action.place != null,
                        )
                        // Plays inside Zugig, so Back comes back here (ADR 0012).
                        is TitleAction.Trailer -> ActionRow(
                            stringResource(R.string.title_trailer),
                            NuageIcons.Movie,
                            onClick = { onSection(TitleSection.Trailer) },
                            modifier = Modifier.testTag("trailer action").focusRequester(trailerAction),
                        )
                        // Its value says what Play starts, and how many Versions there are to choose from instead;
                        // a Show's waits, disabled, while its Episodes are read, so the list doesn't grow under focus.
                        is TitleAction.Versions -> ActionRow(
                            stringResource(R.string.versions_title),
                            Icons.AutoMirrored.Filled.List,
                            onClick = { onSection(TitleSection.Versions) },
                            modifier = Modifier.testTag("versions action").focusRequester(versionsAction),
                            value = action.count?.let { context.versionsValue(action.playing, it) } ?: "",
                            enabled = action.count != null,
                        )
                    }
                }
            }
            // Nothing to play says why under the actions.
            content.nothingToPlay?.let { why ->
                Spacer(Modifier.height(Spacing.s))
                PlayLabel(
                    stringResource(
                        when (why) {
                            NothingToPlay.NoVersion -> R.string.play_nothing
                            NothingToPlay.EpisodesUnreachable -> R.string.episodes_unreachable
                            NothingToPlay.NoEpisodes -> R.string.episodes_none
                        },
                    ),
                )
            }
            // What the Header reads Confirm changes the badges and the Versions value in place; this says why (#457).
            if (hasVersionsAction) CheckingVersions(content.checking, Modifier.padding(top = Spacing.s).testTag("checking versions"))
        }
        Spacer(Modifier.weight(1f))
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(Cards.posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(Corners.card)).testTag(HEADER_POSTER),
            )
        }
    }
}

/** The poster at the right of a Title page's header. */
internal const val HEADER_POSTER = "header poster"

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
