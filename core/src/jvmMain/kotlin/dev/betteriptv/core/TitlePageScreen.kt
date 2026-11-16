package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as Signal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Title's page and the screens its actions open, the Versions screen and a Show's Episode picker
 * ([TitlePageContent]), for a Viewer who reads the [contentLanguages], with the app in the
 * [appLanguage], on the stick and TV [device] tells of (asked at each reading, off the main thread),
 * within the Viewer's [qualityCap].
 *
 * While it is collected the page is open, so the Core does what an open page does: it Header reads
 * the Title's Versions ([Library.readHeaders]), and the content says so and changes with what they
 * Confirm. It is handed again when playback stops, when the Title leaves Continue watching, and
 * when anything else a screen is read from changes ([contentChanges]). A Show's page is handed
 * first without its Episodes when the Provider is still being asked for them, then whole.
 * Nothing is handed for a Title the catalog doesn't have.
 */
data class TitlePageScreen(
    val id: TitleId,
    val contentLanguages: List<String>,
    val appLanguage: String,
    val qualityCap: QualityCap = QualityCap.UHD,
    val preferOriginalAudio: Boolean = true,
    val device: () -> DeviceProfile,
) : Screen<TitlePageContent>() {
    override suspend fun read(library: Library): TitlePageContent =
        checkNotNull(reading(library)) { "The catalog doesn't have $id" }.let { it.content(checking = false, checked = it.versionsChecked) }

    override fun content(library: Library): Flow<TitlePageContent> = channelFlow {
        val reading = MutableStateFlow<Reading?>(null)
        // Whether the Header reads have Versions left to read, and whether they ended, however they did:
        // what they couldn't read stays a guess, which the page shows from then on (#546).
        val checking = MutableStateFlow(false)
        val readsEnded = MutableStateFlow(false)
        launch {
            combine(reading.filterNotNull(), checking, readsEnded) { read, checking, ended -> read.content(checking, checked = ended || read.versionsChecked) }
                .collect { send(it) }
        }
        val changed = Signal<Unit>(Signal.CONFLATED)
        // Its first value is what there is now: the first reading.
        launch { combine(library.contentChanges(), library.progressChanges) { content, progress -> content to progress }.collect { changed.send(Unit) } }
        var headerReads: Job? = null
        val readHeaders = {
            headerReads = launch {
                // Each read that Confirms something has the page read again, in place.
                library.readHeaders(id, contentLanguages, preferOriginalAudio, checking = { left -> checking.value = left > 0 }) { changed.send(Unit) }
                readsEnded.value = true
            }
        }
        // A Show's reads need its show details, so they start once its Episodes are in.
        if (id.kind != TitleKind.Show) readHeaders()
        var first = true
        while (true) {
            changed.receive()
            // Only the first reading shows the page before its Episodes: once read, they never go back to unread.
            val read = reading(library, beforeEpisodes = if (first) { early -> reading.value = early } else null) ?: continue
            first = false
            reading.value = read
            if (headerReads == null && read.episodes != null) readHeaders()
        }
    }.distinctUntilChanged()

    /**
     * What the page is read from, now: the details and what Play starts, and for a Show its Episodes
     * and what playing the next one starts. A Show's details that come before its Episodes are told
     * to [beforeEpisodes]. Null for a Title the catalog doesn't have.
     */
    private suspend fun reading(library: Library, beforeEpisodes: ((Reading) -> Unit)? = null): Reading? = coroutineScope {
        val kids = library.kids
        if (id.kind != TitleKind.Show) {
            val details = library.title(id, contentLanguages, appLanguage, preferOriginalAudio) ?: return@coroutineScope null
            // Read with the details, so a page that arrives checked never shows itself without its badges.
            val checked = library.versionsChecked(id, contentLanguages, preferOriginalAudio)
            return@coroutineScope Reading(details, library.playPlan(id, contentLanguages, device(), qualityCap, preferOriginalAudio), null, kids, checked)
        }
        val asked = async { library.episodes(id, contentLanguages, appLanguage, preferOriginalAudio) }
        val details = library.title(id, contentLanguages, appLanguage, preferOriginalAudio)
        if (details == null) {
            asked.cancel()
            return@coroutineScope null
        }
        val waiting = Reading(details, null, null, kids, versionsChecked = false)
        if (!asked.isCompleted) beforeEpisodes?.invoke(waiting)
        val episodes = asked.await() ?: return@coroutineScope waiting
        val device = device()
        val checked = library.versionsChecked(id, contentLanguages, preferOriginalAudio)
        // A Show's Play plays the Episode to watch next.
        val plan = episodes.next?.let { library.episodePlayPlan(id, it, contentLanguages, device, qualityCap, preferOriginalAudio) }
        Reading(details, plan, episodes, kids, checked)
    }

    /** What the stick and TV can play, asked off the caller's thread: reading it from the TV takes a moment. */
    private suspend fun device(): DeviceProfile = withContext(Dispatchers.Default) { device.invoke() }

    private inner class Reading(val details: TitleDetails, val plan: PlayPlan?, val episodes: ShowEpisodes?, val kids: Boolean, val versionsChecked: Boolean) {
        fun content(checking: Boolean, checked: Boolean) = titlePageContent(details, plan, episodes, contentLanguages, kids, checking, checked)
    }
}

/**
 * What is on a Title's page and behind its actions ([TitlePageScreen]), in domain terms: an app
 * words, lays out and focuses it, and decides none of it.
 *
 * [title] is what the Title is, as the Provider tells it (ADR 0014); on a Kids profile it has no
 * trailer. [best] is the Version the page's badges tell of: the Best playable Version, which Play
 * starts, or with nothing to play the best there is. [actions] are the page's actions in order,
 * the first always the one that plays ([play]). [nothingToPlay] says why when that one starts
 * nothing. [versions] is what the Versions screen lists, null when the page has none to list, and
 * [episodes] what a Show's Episode picker lists, with the Episode it opens on
 * ([ShowEpisodes.next]); null for a Movie, and for a Show while its Episodes are read
 * ([waitingForEpisodes]).
 *
 * While the Header reads are [checking] Versions, what they Confirm changes this content in place.
 * Until the Versions are [checked] (read as far as they can be, and none being read), the page
 * names no quality: an app shows neither [best]'s badges nor a Version's on the Versions screen,
 * which would be a guess the reads then correct (#546).
 */
data class TitlePageContent(
    val title: TitleDetails,
    val best: Version?,
    val actions: List<TitleAction>,
    val nothingToPlay: NothingToPlay?,
    val versions: TitleVersions?,
    val episodes: ShowEpisodes?,
    val checking: Boolean = false,
    val checked: Boolean = true,
) {
    /** The page's first action, the one that plays. */
    val play: TitleAction.Play get() = actions.first() as TitleAction.Play

    /** Whether it is a Show's page whose Episodes are still read: Play and its Episodes and Versions actions wait for them. */
    val waitingForEpisodes: Boolean get() = title.id.kind == TitleKind.Show && episodes == null
}

/** An action on a Title's page ([TitlePageContent.actions]): what it is and what it will do, never its wording. */
sealed interface TitleAction {
    /**
     * The action that plays, the page's first: it starts [plan], or nothing when there is none
     * ([TitlePageContent.nothingToPlay] says why, or a Show's Episodes are still read).
     */
    sealed interface Play : TitleAction {
        val plan: PlayPlan?
    }

    /** Plays a Movie from its start: one the Viewer never played, or Watched. */
    data class PlayFromStart(override val plan: PlayPlan?) : Play

    /** Continues a Movie from where the Viewer stopped in it ([PlayPlan.from], [PlayPlan.progress]). */
    data class Continue(override val plan: PlayPlan) : Play

    /** Plays a Show's [episode], the one to watch next, named [name] when the Provider names it; [start] says why that one. */
    data class PlayEpisode(override val plan: PlayPlan, val episode: EpisodeNumber, val name: String?, val start: EpisodeStart) : Play

    /** Plays what the Viewer stopped in from its beginning ([plan]). */
    data class Restart(val plan: PlayPlan) : TitleAction

    /** Takes the Movie out of Continue watching and forgets where the Viewer stopped ([Library.removeFromContinueWatching]). */
    data object RemoveFromContinueWatching : TitleAction

    /** Opens a Show's Episode picker, saying where the Viewer is in the Show ([place]); null, and nothing to open yet, while its Episodes are read. */
    data class Episodes(val place: EpisodesPlace?) : TitleAction

    /** Plays the Title's trailer, the YouTube video [key] (ADR 0012). */
    data class Trailer(val key: String) : TitleAction

    /**
     * Opens the Versions screen ([TitlePageContent.versions]), saying what Play starts ([playing],
     * null with nothing to play or until the Versions are checked) and how many Versions there are
     * to choose from instead ([count]); null, and nothing to open yet, while a Show's Episodes are read.
     */
    data class Versions(val playing: Version?, val count: Int?) : TitleAction
}

/** Why a Show's Play plays the Episode it does ([TitleAction.PlayEpisode]). */
enum class EpisodeStart {
    /** The Viewer never played the Show: its first Episode. */
    First,

    /** The Viewer stopped in this Episode: it continues from there. */
    StoppedIn,

    /** The Viewer has played some of the Show and isn't stopped in this Episode: the one after the last they Watched. */
    Next,
}

/** Where the Viewer is in a Show, as its Episodes action says it ([TitleAction.Episodes]). */
sealed interface EpisodesPlace {
    /** They have played some of it: the Episode to watch next. */
    data class At(val episode: EpisodeNumber) : EpisodesPlace

    /** They never played it: the [season] it starts in, and how many [episodes] that season has. */
    data class NotStarted(val season: Int, val episodes: Int) : EpisodesPlace
}

/** Why a Title's page has nothing to play ([TitlePageContent.nothingToPlay]). */
enum class NothingToPlay {
    /** No Version the stick and TV can play. */
    NoVersion,

    /** The Provider couldn't be asked for the Show's Episodes, and none are kept. */
    EpisodesUnreachable,

    /** The Provider has no Episode of the Show. */
    NoEpisodes,
}

/**
 * What a Versions screen lists: the Versions of a Movie, or of a Show's [episode] (named
 * [episodeName] when the Provider names it), the one its Play starts, else the next to watch.
 * They are grouped by language, the Viewer's [languages] first and the [otherLanguages] behind.
 * With the Viewer's [contentLanguages] and the Title's [originalLanguage] (when Prefer original
 * audio acts on it), a Version whose audio is in one other language can say so.
 */
data class TitleVersions(
    val episode: EpisodeNumber?,
    val episodeName: String?,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
    val contentLanguages: List<String> = emptyList(),
    val originalLanguage: String? = null,
) {
    /** Every Version, Backups included. */
    val count: Int get() = (languages + otherLanguages).sumOf { it.versionCount }
}

/**
 * A Title's page from what it is read from: its details ([title]), what Play starts ([plan]: a
 * Movie's plan, or the plan of a Show's next Episode; null with nothing to play) and a Show's
 * [episodes] (null for a Movie, and for a Show while they are read), on a Kids profile or not
 * ([kids]), with the Header reads [checking] Versions or not and the Versions [checked] or not.
 * The Library builds a page this way ([TitlePageScreen]); it is public so that a screen test can
 * stand a page in.
 */
fun titlePageContent(
    title: TitleDetails,
    plan: PlayPlan?,
    episodes: ShowEpisodes?,
    contentLanguages: List<String> = emptyList(),
    kids: Boolean = false,
    checking: Boolean = false,
    checked: Boolean = true,
): TitlePageContent {
    val isShow = title.id.kind == TitleKind.Show
    val waiting = isShow && episodes == null
    // Never while a Header read is under way, as what it Confirms would change it.
    val named = checked && !checking
    val next = episodes?.next
    val nextEpisode = next?.let { episodes.episode(it) }
    // A Kids page has no Versions to choose from: Play picks the Best playable Version, as on any profile.
    val versions = if (kids) null else versionsOf(title, plan, episodes, contentLanguages)
    val resumable = plan != null && plan.from > 0
    // Having played any of the Show, the Viewer continues it.
    val started = episodes?.seasons.orEmpty().any { season -> season.episodes.any { it.progress != null } }
    val number = plan?.episode
    val play = when {
        number != null -> TitleAction.PlayEpisode(
            plan, number, nextEpisode?.takeIf { it.id == number }?.name,
            when {
                resumable -> EpisodeStart.StoppedIn
                started -> EpisodeStart.Next
                else -> EpisodeStart.First
            },
        )
        resumable -> TitleAction.Continue(plan)
        else -> TitleAction.PlayFromStart(plan)
    }
    val actions = buildList {
        add(play)
        if (resumable) {
            add(TitleAction.Restart(plan.fromStart()))
            // Continue watching holds Movies: an Episode's place is kept with the Show.
            if (!isShow) add(TitleAction.RemoveFromContinueWatching)
        }
        // A Show's Episodes action is there while they're read, and left out of a Show with none.
        if (isShow && (episodes == null || next != null)) {
            val season = next?.let { number -> episodes.seasons.firstOrNull { it.number == number.season } }
            add(
                TitleAction.Episodes(
                    when {
                        next == null || season == null -> null
                        started -> EpisodesPlace.At(next)
                        else -> EpisodesPlace.NotStarted(next.season, season.episodes.size)
                    },
                ),
            )
        }
        // No Trailer on a Kids page: YouTube's end screen shows what it likes.
        if (title.trailer != null && !kids) add(TitleAction.Trailer(title.trailer))
        // A Show's Versions action waits while its Episodes are read.
        if (!kids && (versions != null || waiting)) add(TitleAction.Versions(plan?.version?.takeIf { named }, versions?.count))
    }
    return TitlePageContent(
        title = if (kids) title.copy(trailer = null) else title,
        // The badges name the Version Play starts, as the Versions action does, so the two agree (#515):
        // a better one the TV can't display or the Quality cap leaves out, or a Show's Listing, would
        // name a picture Play never gives. Only with nothing to play do they name the best Version, and
        // with no Version in the Viewer's languages, the other languages are all there is to show.
        best = plan?.version ?: title.languages.ifEmpty { title.otherLanguages }.firstOrNull()?.versions?.firstOrNull(),
        actions = actions,
        // Once a Show's Episodes are read, as their wait is no reason.
        nothingToPlay = when {
            plan != null || waiting -> null
            episodes == null || episodes.seasons.isNotEmpty() -> NothingToPlay.NoVersion
            episodes.unreadVersions > 0 -> NothingToPlay.EpisodesUnreachable
            else -> NothingToPlay.NoEpisodes
        },
        versions = versions,
        episodes = episodes,
        checking = checking,
        checked = named,
    )
}

/**
 * What [title]'s Versions screen lists: a Movie's Versions, or on a Show those of the Episode
 * [plan] starts, else of its next Episode ([ShowEpisodes.next]). Null on a Show whose Episodes
 * aren't read yet, or that has none to play.
 */
private fun versionsOf(title: TitleDetails, plan: PlayPlan?, episodes: ShowEpisodes?, contentLanguages: List<String>): TitleVersions? {
    // The details name the original language only when Prefer original audio acts on the Title.
    val original = title.originalLanguage
    if (title.id.kind != TitleKind.Show) return TitleVersions(null, null, title.languages, title.otherLanguages, contentLanguages, original)
    val number = plan?.episode ?: episodes?.next ?: return null
    val episode = episodes?.episode(number) ?: return null
    return TitleVersions(number, episode.name, episode.languages, episode.otherLanguages, contentLanguages, original)
}

private fun ShowEpisodes.episode(number: EpisodeNumber): Episode? =
    seasons.firstOrNull { it.number == number.season }?.episodes?.firstOrNull { it.id == number }
