package dev.betteriptv.core

import kotlinx.coroutines.channels.Channel as Signal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A screen an app asks the Core for ([Library.screen]), with what its content is read for: the
 * Viewer's languages, Region and Leagues, which the app keeps. Its [Content] is what is on the
 * screen, finished and in domain terms: the Rows in order with their cards, never layout, focus,
 * colour or translated wording, which each app draws and words by itself (ADR 0020).
 *
 * A new screen is a class here with its content beside it: it says how its content is read from the
 * [Library] ([read]) and, when time alone changes it, how soon ([readAgainAfter]). Everything else
 * (when to read again, and telling the app) is [Library.screen]'s, the same for every screen.
 */
sealed class Screen<Content : Any> {
    /** What is on the screen now, for the Profile the device is on. */
    internal abstract suspend fun read(library: Library): Content

    /**
     * How long [content] stands before it is read again though nothing the Core knows of changed,
     * or null when only a change makes it stale.
     */
    internal open fun readAgainAfter(content: Content): Duration? = null

    /**
     * Home ([HomeContent]), for a Viewer who reads the [contentLanguages], with the app in the
     * [appLanguage], in the [region], following the [leagues].
     */
    data class Home(
        val contentLanguages: List<String>,
        val appLanguage: String,
        val region: String = Library.DEFAULT_REGION,
        val leagues: List<League> = emptyList(),
    ) : Screen<HomeContent>() {
        override suspend fun read(library: Library) =
            HomeContent(library.homeRows(contentLanguages, appLanguage, region, leagues, Clock.System.now()).withUniqueCards())

        // Live now's Games go live and end by the clock: while the row shows, the Games are asked for again.
        override fun readAgainAfter(content: HomeContent) = GAMES_READ_AGAIN_AFTER.takeIf { content.rows.any { it is HomeRow.LiveNow } }
    }
}

/**
 * What is on Home: its [rows] in order, top to bottom, each with its cards in order. A Kids
 * profile's hold only the Kids catalog. Each Row has its own [key], and each of its cards its own
 * key in it ([cardKeys]); a Title or a Game is in one Row only, the highest it fits, Continue
 * watching and Favourites standing apart (CONTEXT.md: Row). A Title's card carries what its badges
 * say as of this reading ([TitleSummary.best]); a Version Confirmed later is told apart
 * ([Library.confirmations]).
 *
 * Which Rows there are, and what a Row leaves out, is [Library.homeRows]'s.
 */
data class HomeContent(val rows: List<HomeRow>)

/**
 * [screen]'s content as live state ([Library.screen]): read once collecting starts, then again
 * whenever what it is read from changes ([contentChanges]) or it has stood for [readAgainAfter], and
 * emitted when it differs from the last. One reading runs at a time; changes that land during it are
 * answered by one more.
 */
internal fun <Content : Any> Library.liveContent(
    screen: Screen<Content>,
    readAgainAfter: (Content) -> Duration? = screen::readAgainAfter,
): Flow<Content> = flow {
    coroutineScope {
        val changed = Signal<Unit>(Signal.CONFLATED)
        // Its first value is what there is now: the first reading.
        launch { contentChanges().collect { changed.send(Unit) } }
        var content: Content? = null
        while (true) {
            val wait = content?.let(readAgainAfter)
            if (wait == null) changed.receive() else withTimeoutOrNull(wait) { changed.receive() }
            content = screen.read(this@liveContent)
            emit(content)
        }
    }
}.distinctUntilChanged()

/**
 * Changes when what a screen shows may have: a catalog landed (a Head start's and a Pending catalog
 * too), Matches regrouped its Titles, Title facts or the Account's history landed, fresh Games
 * arrived ([Library.catalogChanges]), or the device went to another Profile, or the one it is on
 * became a Kids profile or stopped being one ([Library.profiles]).
 */
private fun Library.contentChanges(): Flow<Any> =
    combine(catalogChanges, profiles) { catalog, profiles -> catalog to profiles.current }.distinctUntilChanged()

/** How often Home asks for the Games again while Live now shows (docs/roadmap.md, M3): the cloud reads a League's day every 5 minutes. */
internal val GAMES_READ_AGAIN_AFTER: Duration = 2.minutes
