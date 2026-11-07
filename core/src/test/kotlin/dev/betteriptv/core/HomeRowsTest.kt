package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The home screen's rows, seen through the Library after a Sync, with metadata from the fake Cloud. */
class HomeRowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")

    private fun movie(tmdbId: Long) = TitleId.Tmdb(TitleKind.Movie, tmdbId)

    /** Syncs these movie Listings, newest first, numbered from 1, each a Title of its own TMDB ID. */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    /** Serves TMDB's metadata of a Title [tmdbId] named [name]. */
    private fun tmdb(tmdbId: Long, name: String, popularity: Double = 10.0, genres: List<String> = emptyList()) =
        cloud.title("en", tmdbTitle(tmdbId, name, genres = genres, popularity = popularity, backdropPath = "/still-$tmdbId.jpg"))

    private suspend fun rows(contentLanguages: List<String> = english, region: String = "CA", now: Instant = Instant.now(), leagues: List<League> = emptyList()) =
        library.homeRows(contentLanguages, appLanguage = "en", region = region, leagues = leagues, now = now)

    /** Serves these show Listings at the next Sync, numbered from 1, each a Title of its own TMDB ID. */
    private fun serveShows(vararg listings: Pair<String, Long>) {
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"2","last_modified":"${1_700_000_000 - i}"}"""
        }
    }

    private fun List<HomeRow>.top(list: ChartList) = filterIsInstance<HomeRow.Chart>().singleOrNull { it.list == list }

    private fun List<HomeRow>.topNames(list: ChartList) = top(list)?.titles?.map { it.name }

    private fun List<HomeRow>.new() = filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.name }

    private fun List<HomeRow>.genre(name: String) = filterIsInstance<HomeRow.Genre>().single { it.name == name }.titles.map { it.name }

    @Test fun `New on your service puts the most popular new Titles first`() = runBlocking {
        syncMovies("EN - Quiet (2025)" to 1, "EN - Hit (2025)" to 2, "EN - Middling (2025)" to 3)
        tmdb(1, "Quiet", popularity = 2.0)
        tmdb(2, "Hit", popularity = 90.0)
        tmdb(3, "Middling", popularity = 30.0)

        assertEquals(listOf("Hit", "Middling", "Quiet"), rows().new())
    }

    @Test fun `browse rows show only Titles in a Content language, and Untagged ones Likely in one`() = runBlocking {
        // Wikidata says which language the Untagged Titles were released in; the device learns it at the Sync (#476).
        cloud.titleFacts(movie(3), originalLanguage = "en")
        cloud.titleFacts(movie(4), originalLanguage = "fr")
        syncMovies(
            "EN - English (2025)" to 1,
            "FR - Français (2025)" to 2,
            "4K - Untagged English (2025)" to 3,
            "4K - Untagged French (2025)" to 4,
            "FR - Both (2025)" to 5,
            "EN - Both (2025)" to 5,
        )
        tmdb(1, "English")
        tmdb(2, "Français")
        tmdb(3, "Untagged English")
        tmdb(4, "Untagged French")
        tmdb(5, "Both")

        assertEquals(setOf("English", "Untagged English", "Both"), rows(english).new().toSet())
        assertEquals(setOf("Français", "Untagged French", "Both"), rows(listOf("fr-CA")).new().toSet())
    }

    /** The 40 newest movies New on your service is picked from, so the Titles synced after them are left to the genre rows. */
    private fun newest() = (1L..40L).map { "EN - New $it (2025)" to 1000 + it }

    @Test fun `genre rows hold the Titles of each TMDB genre, most popular first`() = runBlocking {
        syncMovies(*(newest() + (1L..6L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..6L).forEach { tmdb(it, "Film $it", popularity = it.toDouble(), genres = if (it == 6L) listOf("Drama", "Comedy") else listOf("Drama")) }

        val rows = rows()
        assertEquals((6 downTo 1).map { "Film $it" }, rows.genre("Drama"))
        // One comedy isn't a row.
        assertTrue(rows.none { it is HomeRow.Genre && it.name == "Comedy" })
    }

    @Test fun `Continue watching shows what the Viewer stopped in, last played first, until they finish it`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "FR - Foreign (2025)" to 3)
        tmdb(1, "First")
        tmdb(2, "Second")
        tmdb(3, "Foreign")
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)
        Thread.sleep(5)
        library.saveProgress(movie(3), position = 10 * 60_000L, duration = null)
        Thread.sleep(5)
        library.saveProgress(movie(2), position = 60 * 60_000L, duration = 120 * 60_000L)

        var resumes = rows().filterIsInstance<HomeRow.ContinueWatching>().single().titles
        // What the Viewer started shows whatever its language.
        assertEquals(listOf("Second", "Foreign", "First"), resumes.map { it.title.name })
        assertEquals(listOf(0.5f, null, 0.25f), resumes.map { it.progress })
        assertEquals(listOf(Duration.ofMinutes(60), null, Duration.ofMinutes(90)), resumes.map { it.left })
        assertEquals("https://image.tmdb.org/t/p/w1280/still-2.jpg", resumes.first().still)
        assertTrue(rows().first() is HomeRow.ContinueWatching)

        library.finished(movie(2))
        resumes = rows().filterIsInstance<HomeRow.ContinueWatching>().single().titles
        assertEquals(listOf("Foreign", "First"), resumes.map { it.title.name })
    }

    @Test fun `a card carries what the info area under it shows, from the Provider's Category and rating alone`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - DRAMA"},{"category_id":"2","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[
            {"stream_id":1,"name":"EN - Described (2025)","tmdb":"1","category_id":"1","added":"1700000000","rating":"6.6"},
            {"stream_id":2,"name":"EN - Unknown (2025)","tmdb":"2","category_id":"2","added":"1699999999","rating":"0"}
        ]"""
        library.sync()
        // TMDB's description, genres and runtime no longer feed the info (#473).
        cloud.title("en", tmdbTitle(1, "Described", descriptions = listOf(Triple("en", "", "A film.")), genres = listOf("Comedy"), runtimeMinutes = 95))
        provider.onRequest = { action -> check(action != "get_vod_info") { "a row made a details call" } }

        val cards = rows().filterIsInstance<HomeRow.NewOnYourService>().single().titles.associateBy { it.name }
        assertEquals(TitleAbout(listOf("Drama"), 6.6), cards.getValue("Described").about)
        // A Category naming no genre and a rating of zero: nothing to tell.
        assertEquals(TitleAbout(), cards.getValue("Unknown").about)
    }

    @Test fun `without anything started there's no Continue watching row`() = runBlocking {
        syncMovies("EN - First (2025)" to 1)
        tmdb(1, "First")

        assertTrue(rows().none { it is HomeRow.ContinueWatching })
    }

    @Test fun `a Title in New on your service doesn't show again in a genre row`() = runBlocking {
        syncMovies(*(newest() + (1L..5L).map { "EN - Old $it (2025)" to it }).toTypedArray())
        tmdb(1001, "New 1", genres = listOf("Drama"))
        (1L..5L).forEach { tmdb(it, "Old $it", popularity = it.toDouble(), genres = listOf("Drama")) }

        val rows = rows()
        assertTrue("New 1" in rows.new())
        assertEquals((5 downTo 1).map { "Old $it" }, rows.genre("Drama"))
    }

    @Test fun `a Title in several genres shows only in the highest genre row`() = runBlocking {
        // Drama has 7 Titles and Comedy 6, Film 1 in both: Drama is the higher row.
        syncMovies(*(newest() + (1L..12L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..12L).forEach {
            val genres = when {
                it == 1L -> listOf("Comedy", "Drama")
                it <= 7L -> listOf("Drama")
                else -> listOf("Comedy")
            }
            tmdb(it, "Film $it", popularity = it.toDouble(), genres = genres)
        }

        val rows = rows()
        assertEquals(listOf("Drama", "Comedy"), rows.filterIsInstance<HomeRow.Genre>().map { it.name })
        assertTrue("Film 1" in rows.genre("Drama"))
        assertEquals((12 downTo 8).map { "Film $it" }, rows.genre("Comedy"))
    }

    @Test fun `a Title in Continue watching still shows in the rows below it`() = runBlocking {
        syncMovies(*(newest() + (1L..5L).map { "EN - Old $it (2025)" to it }).toTypedArray())
        tmdb(1001, "New 1")
        (1L..5L).forEach { tmdb(it, "Old $it", genres = listOf("Drama")) }
        library.saveProgress(movie(1001), position = 30 * 60_000L, duration = 120 * 60_000L)
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(setOf("New 1", "Old 1"), rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.name }.toSet())
        assertTrue("New 1" in rows.new())
        assertTrue("Old 1" in rows.genre("Drama"))
    }

    @Test fun `a genre row left under 5 Titles is left out, and the next genre takes its place`() = runBlocking {
        // Drama has 7 Titles; Thriller 5, one of them a Drama; then 7 genres of 5. Eight genre rows fit.
        val others = (1..7).map { "Genre $it" }
        val films = (1L..7L).map { it to if (it == 1L) listOf("Drama", "Thriller") else listOf("Drama") } +
            (8L..11L).map { it to listOf("Thriller") } +
            others.withIndex().flatMap { (i, genre) -> (1..5).map { n -> 12L + i * 5 + n - 1 to listOf(genre) } }
        syncMovies(*(newest() + films.map { (id, _) -> "EN - Film $id (2025)" to id }).toTypedArray())
        films.forEach { (id, genres) -> tmdb(id, "Film $id", genres = genres) }

        assertEquals(listOf("Drama") + others, rows().filterIsInstance<HomeRow.Genre>().map { it.name })
    }

    // Charts

    @Test fun `Home's rows go Continue watching, Top movies, Top shows, New, Trending, Favourites, then genres, with no Game on`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies(*(newest() + (1L..8L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..8L).forEach { tmdb(it, "Film $it", genres = listOf("Drama")) }
        cloud.charts("CA", movies = listOf(1001, 1002, 1003), shows = listOf(501, 502, 503))
        cloud.trending(movie(6), movie(7), movie(8))
        library.setFavourite(library.search("ESPN", english, "en").last().channels.single().key, true)
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(withoutLiveNow, rows.names())
        assertEquals(listOf("Show 1", "Show 2", "Show 3"), rows.topNames(ChartList.TopShows))
    }

    /** Each row's name, so a whole screen reads as a list. */
    private fun List<HomeRow>.names() = map {
        when (it) {
            is HomeRow.ContinueWatching -> "continue"
            is HomeRow.LiveNow -> "live now"
            is HomeRow.Chart -> when (it.list) {
                ChartList.TopMovies -> "top movies"
                ChartList.TopShows -> "top shows"
                ChartList.Trending -> "trending"
                ChartList.TrendingMovies, ChartList.TrendingShows -> "a tab's trending"
            }
            is HomeRow.NewOnYourService -> "new"
            is HomeRow.Favourites -> "favourites"
            is HomeRow.Channels -> "channels"
            is HomeRow.Genre -> "genre"
            is HomeRow.Today, is HomeRow.LeagueGames, is HomeRow.MoreLiveEvents, is HomeRow.Replays -> "a Live row"
            is HomeRow.NewForKids, is HomeRow.ForKids, is HomeRow.KidsChannels -> "a Kids profile's row"
        }
    }

    private val withLiveNow = listOf("continue", "live now", "top movies", "top shows", "new", "trending", "favourites", "genre")
    private val withoutLiveNow = listOf("continue", "top movies", "top shows", "new", "trending", "favourites", "genre")

    // Live now (#256)

    /** How a Provider names the start of a Game [ago] before now, in its `(2026-10-29 18:00:30)` style. */
    private fun startedAgo(ago: Duration) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((Instant.now() - ago).atZone(surveyedPrefixTable().eventTimeZone))

    /** The Event channel carrying tonight's Game, started two hours ago (named once, so the clock ticking over a second doesn't rename it). */
    private val eventChannel by lazy { "NHL: Canadiens vs Maple Leafs (${startedAgo(Duration.ofHours(2))})" }

    /** Every other Home row, with a Game started two hours ago among the NHL's (the NFL's slate empty) and, unless [withEvent] is off, its Event channel on the Provider. */
    private suspend fun serveHomeWithGames(gameState: String = "live", withEvent: Boolean = true) {
        provider.serveChannels(*listOfNotNull("US: ESPN HD" to "US| SPORT", (eventChannel to "US| ESPN+ PPV").takeIf { withEvent }).toTypedArray())
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies(*(newest() + (1L..8L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..8L).forEach { tmdb(it, "Film $it", genres = listOf("Drama")) }
        cloud.charts("CA", movies = listOf(1001, 1002, 1003), shows = listOf(501, 502, 503))
        cloud.trending(movie(6), movie(7), movie(8))
        cloud.games("nhl", game("1", (Instant.now() - Duration.ofHours(2)).toString(), state = gameState, home = "Toronto Maple Leafs", away = "Montreal Canadiens"))
        cloud.games("nfl")
        library.setFavourite(library.search("ESPN", english, "en").last().channels.single().key, true)
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)
    }

    /**
     * Runs [read], a Home read expected to ask the cloud for Games behind it, and waits until the
     * fresh Games have landed ([Library.catalogChanges]), as Home then reads its rows again.
     */
    private suspend fun <T> withGamesLanded(read: suspend () -> T): T {
        val before = library.catalogChanges.value
        val result = read()
        withTimeout(10_000) { library.catalogChanges.first { it != before } }
        return result
    }

    @Test fun `while a Game with a Feed is on, Live now comes right after Continue watching, with the hub's Game cards`() = runBlocking {
        serveHomeWithGames()

        // Home's first read answers with the Games kept (none yet) and asks the cloud behind it; the next read has them.
        assertEquals(withoutLiveNow, withGamesLanded { rows(leagues = listOf(League.Nhl, League.Nfl)) }.names())
        val rows = rows(leagues = listOf(League.Nhl, League.Nfl))
        assertEquals(withLiveNow, rows.names())
        val live = rows.filterIsInstance<HomeRow.LiveNow>().single().games.single()
        assertEquals("1", live.game.id)
        assertEquals(listOf(eventChannel), live.feeds.map { it.channel.variants.first().name })
        assertEquals(listOf(listOf("nhl", "nfl")), cloud.gamesAsked)
    }

    @Test fun `Home is unchanged with no League on, a live Game with no Feed, or a Game not yet started`() = runBlocking {
        serveHomeWithGames(withEvent = false)
        withGamesLanded { rows(leagues = listOf(League.Nhl)) }
        assertEquals(withoutLiveNow, rows(leagues = listOf(League.Nhl)).names())
        // With no League on, the cloud isn't asked for Games at all.
        assertEquals(withoutLiveNow, rows().names())
        assertEquals(1, cloud.gamesAsked.size)

        // The Game and its Event channel two hours ahead instead.
        cloud.games("nhl", game("2", (Instant.now() + Duration.ofHours(2)).toString(), home = "Toronto Maple Leafs", away = "Montreal Canadiens"))
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "NHL: Canadiens vs Maple Leafs (${startedAgo(Duration.ofHours(-2))})" to "US| ESPN+ PPV")
        library.sync()
        val later = Instant.now() + Duration.ofMinutes(2)
        withGamesLanded { rows(now = later, leagues = listOf(League.Nhl)) }
        assertEquals(withoutLiveNow, rows(now = later, leagues = listOf(League.Nhl)).names())
    }

    @Test fun `a League the cloud has no copy of yet doesn't keep Home reading its rows again`() = runBlocking {
        serveHomeWithGames()
        // The Champions League just turned on: the cloud reads it within minutes, and leaves it out of its answers till then (#307).
        val leagues = listOf(League.Nhl, League.ChampionsLeague)
        withGamesLanded { rows(leagues = leagues) }

        // Home reads again since the NHL's Games landed: nothing is due for the next minute, so nothing lands behind it to read again for.
        val landed = library.catalogChanges.value
        assertEquals(withLiveNow, rows(leagues = leagues).names())
        delay(500)
        assertEquals(landed, library.catalogChanges.value)
        assertEquals(1, cloud.gamesAsked.size)

        // A minute on, the Champions League is asked for again.
        withGamesLanded { rows(now = Instant.now() + Duration.ofSeconds(61), leagues = leagues) }
        assertEquals(2, cloud.gamesAsked.size)
    }

    @Test fun `Home still builds when the cloud has no Games for the Leagues on, or is down`() = runBlocking {
        serveHomeWithGames()
        cloud.forgetGames("nhl")
        assertEquals(withoutLiveNow, rows(leagues = listOf(League.Nhl)).names())

        cloud.down = true
        val rows = rows(now = Instant.now() + Duration.ofMinutes(2), leagues = listOf(League.Nhl))
        assertEquals(withoutLiveNow, rows.names())
        assertEquals(listOf("Film 1"), rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.name })
    }

    @Test fun `Home's rows don't wait on a hanging cloud, the Games kept standing until the fresh ones come behind`() = runBlocking {
        serveHomeWithGames()
        val hang = CountDownLatch(1)
        cloud.hangingGames = hang
        try {
            // Well under the Games request's 3 s call timeout, let alone a hanging socket's minutes.
            val rows = withTimeout(1_500) { rows(leagues = listOf(League.Nhl)) }
            assertEquals(withoutLiveNow, rows.names())
            assertEquals(listOf(listOf("nhl")), cloud.gamesAsked)
        } finally {
            cloud.hangingGames = null
            hang.countDown()
        }
    }

    @Test fun `a cloud that fails after a copy was kept leaves Live now standing`() = runBlocking {
        serveHomeWithGames()
        withGamesLanded { rows(leagues = listOf(League.Nhl)) }
        assertEquals(withLiveNow, rows(leagues = listOf(League.Nhl)).names())

        cloud.down = true
        // Two minutes on, the copy is due again: the cloud is asked behind the read and fails, and the copy stands.
        assertEquals(withLiveNow, rows(now = Instant.now() + Duration.ofMinutes(2), leagues = listOf(League.Nhl)).names())
        withTimeout(10_000) { while (cloud.gamesAsked.size < 2) delay(50) }
        assertEquals(withLiveNow, rows(now = Instant.now() + Duration.ofMinutes(3), leagues = listOf(League.Nhl)).names())
    }

    @Test fun `a Top row keeps the Provider's Titles in a Content language, in rank order without gaps`() = runBlocking {
        syncMovies("EN - Fourth (2025)" to 4, "EN - First (2025)" to 1, "FR - Second (2025)" to 2, "EN - Fifth (2025)" to 5)
        tmdb(1, "First")
        tmdb(2, "Second")
        tmdb(4, "Fourth")
        tmdb(5, "Fifth")
        // Netflix's #2 isn't on the Provider; #3 has no Version in English.
        cloud.charts("CA", movies = listOf(1, 99, 2, 4, 5))

        val top = rows().top(ChartList.TopMovies)!!
        assertEquals(listOf("First", "Fourth", "Fifth"), top.titles.map { it.name })
        assertEquals("CA", top.region)
    }

    @Test fun `a Top row of fewer than 3 cards is left out`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)
        cloud.charts("CA", movies = listOf(1, 2, 3))

        val rows = rows()
        assertEquals(null, rows.top(ChartList.TopMovies))
        // Its Titles stay in the rows below.
        assertEquals(setOf("First", "Second"), rows.new().toSet())
    }

    @Test fun `the worldwide Top 10 says it is the world's`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3), worldwide = true)

        assertEquals(null, rows().top(ChartList.TopMovies)!!.region)
    }

    @Test fun `the Charts are the Region's`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.charts("FR", movies = listOf(3, 2, 1))

        assertEquals(listOf("First", "Second", "Third"), rows(region = "CA").topNames(ChartList.TopMovies))
        assertEquals(listOf("Third", "Second", "First"), rows(region = "FR").topNames(ChartList.TopMovies))
        assertEquals("FR", rows(region = "FR").top(ChartList.TopMovies)!!.region)
    }

    @Test fun `a Chart keeps all its Titles, the rows below skip them, and Continue watching keeps its own`() = runBlocking {
        syncMovies(*newest().toTypedArray())
        cloud.charts("CA", movies = listOf(1001, 1002, 1003))
        library.saveProgress(movie(1001), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(listOf("New 1", "New 2", "New 3"), rows.topNames(ChartList.TopMovies))
        assertTrue(rows.new().none { it in setOf("New 1", "New 2", "New 3") })
        assertEquals(listOf("New 1"), rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.name })
    }

    @Test fun `the Charts are kept, asked for again after an hour, and the kept ones used while the cloud fails`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        rows()
        rows()
        assertEquals(listOf("CA"), cloud.chartsAsked)

        cloud.down = true
        val later = rows(now = Instant.now() + Duration.ofHours(2))
        assertEquals(listOf("CA", "CA"), cloud.chartsAsked)
        assertEquals(listOf("First", "Second", "Third"), later.topNames(ChartList.TopMovies))
        assertEquals(listOf("Fourth"), later.new())
    }

    @Test fun `without the cloud and no Charts kept, Home has every other row`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.down = true

        val rows = rows()
        assertTrue(rows.none { it is HomeRow.Chart })
        assertEquals(setOf("First", "Second", "Third"), rows.new().toSet())
    }

    @Test fun `Trending this week mixes movies and shows the Provider has in a Content language, skipping what's shown above`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "FR - Série (2025)" to 502)
        syncMovies(*(newest() + (1L..4L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        tmdb(1, "Film 1")
        cloud.title("en", tmdbTitle(502, "Série", kind = "show", originalLanguage = "fr"))
        cloud.charts("CA", movies = listOf(2, 3, 4))
        // New 1 shows in New on your service, Film 2 in Top movies; 99 isn't on the Provider; the Série isn't in English.
        cloud.trending(movie(1), TitleId.Tmdb(TitleKind.Show, 501), movie(1001), movie(2), movie(99), TitleId.Tmdb(TitleKind.Show, 502), movie(1040))

        val rows = rows()
        assertEquals(listOf("Film 1", "Show 1"), rows.topNames(ChartList.Trending)?.take(2))
        assertTrue(rows.top(ChartList.Trending)!!.titles.none { it.name in setOf("New 1", "Film 2", "Série") })
    }

    @Test fun `Trending with fewer than 3 cards left is left out`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)
        cloud.trending(movie(1), movie(2))

        assertEquals(null, rows().top(ChartList.Trending))
    }

    @Test fun `a Title's details carry its Top 10 rank, as its Top row numbers it`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies("EN - First (2025)" to 1)
        cloud.charts("CA", movies = listOf(1), shows = listOf(599, 503, 502, 501))

        val show = TitleId.Tmdb(TitleKind.Show, 502)
        assertEquals(ChartRank(2, ChartList.TopShows, "CA"), library.title(show, english, "en", region = "CA")!!.rank)
        assertEquals(listOf("Show 3", "Show 2", "Show 1"), rows().topNames(ChartList.TopShows))
        assertEquals(null, library.title(movie(1), english, "en", region = "FR")!!.rank)
    }

    @Test fun `Home reads what's stored while a Sync downloads, though its Charts and metadata are asked again`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        // An answer from before textless backdrops is asked again on every read, so this one saves metadata each time.
        cloud.title("en", tmdbTitle(4, "Fourth") - "textlessBackdropPath")
        rows()
        // The Sync stops in the middle of the show list, holding the catalog's writer, until released.
        val downloading = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.onRequest = { action ->
            if (action == "get_series") {
                downloading.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val sync = launch(Dispatchers.IO) { library.sync() }
        try {
            assertTrue(downloading.await(10, TimeUnit.SECONDS))
            // Over an hour later, so the kept Charts are asked again.
            val later = Instant.now() + Duration.ofHours(2)
            val rows = withTimeout(5_000) { rows(now = later) }
            assertEquals(listOf("CA", "CA"), cloud.chartsAsked)
            assertEquals(listOf("First", "Second", "Third"), rows.topNames(ChartList.TopMovies))
            assertEquals(listOf("Fourth"), rows.new())
            // The answer is kept meanwhile: the next read doesn't ask again.
            withTimeout(5_000) { rows(now = later) }
            assertEquals(listOf("CA", "CA"), cloud.chartsAsked)
        } finally {
            release.countDown()
            sync.join()
        }
        provider.onRequest = null
        rows(now = Instant.now() + Duration.ofHours(2))
        assertEquals(listOf("CA", "CA"), cloud.chartsAsked)
    }
}
