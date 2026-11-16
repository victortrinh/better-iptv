package dev.betteriptv.core

/**
 * A Row's key on its screen: the same Row keeps it when the Rows before it come or go, so an app can
 * tell a Row that stayed from one that arrived, whatever its place. No two Rows of a screen share one.
 */
val HomeRow.key: String
    get() = when (this) {
        is HomeRow.ContinueWatching -> "continue"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.Channels -> "channels"
        is HomeRow.Trending -> "trending"
        is HomeRow.NewOnYourService -> "new"
        is HomeRow.NewForKids -> "new-for-kids"
        is HomeRow.ForKids -> "for-kids/$kind"
        is HomeRow.KidsChannels -> "kids-channels/${country.code}"
        is HomeRow.Genre -> "genre/$name"
        is HomeRow.LiveNow -> "live-now"
        is HomeRow.Today -> "today"
        is HomeRow.LeagueGames -> "league/${league.key}"
        is HomeRow.MoreLiveEvents -> "more-live-events"
        is HomeRow.Replays -> "replays"
    }

/** The keys of a Row's cards, in the Row's order: each card's Title, Channel, country, Game or Sports replay ([cardKey]). */
val HomeRow.cardKeys: List<String>
    get() = when (this) {
        is HomeRow.ContinueWatching -> titles.map { it.title.id.cardKey }
        is HomeRow.Favourites -> channels.map { it.cardKey }
        is HomeRow.KidsChannels -> channels.map { it.cardKey }
        is HomeRow.Channels -> countries.map { it.cardKey }
        is HomeRow.Trending -> titles.map { it.id.cardKey }
        is HomeRow.NewOnYourService -> titles.map { it.id.cardKey }
        is HomeRow.NewForKids -> titles.map { it.id.cardKey }
        is HomeRow.ForKids -> titles.map { it.id.cardKey }
        is HomeRow.Genre -> titles.map { it.id.cardKey }
        is HomeRow.LiveNow -> games.map { it.cardKey }
        is HomeRow.Today -> games.map { it.cardKey }
        is HomeRow.LeagueGames -> games.map { it.cardKey }
        is HomeRow.MoreLiveEvents -> events.map { it.channel.cardKey }
        is HomeRow.Replays -> replays.map { it.cardKey }
    }

/** A Title's card's key in its Row ([cardKeys]). */
val TitleId.cardKey: String get() = toString()

/** A Channel's card's key in its Row ([cardKeys]). */
val Channel.cardKey: String get() = "channel/$key"

/** A country's card's key in the Channels Row ([cardKeys]). */
val LiveCountry.cardKey: String get() = "country/$code"

/** A Game's card's key in its Row ([cardKeys]). */
val GameFeeds.cardKey: String get() = "game/$key"

/** A Sports replay's card's key in the Replays Row ([cardKeys]). */
val LeagueReplay.cardKey: String get() = "replay/${replay.listingId}"

/**
 * These Rows with each Row once and each card once in its Row, where it first comes: an app keys what
 * it draws by [key] and [cardKeys], and a key used twice closes the Android app (#378). Each Row is
 * built with a Title once already, so this only stands guard over a list that slips one in twice.
 */
fun List<HomeRow>.withUniqueCards(): List<HomeRow> = distinctBy { it.key }.map { row ->
    when (row) {
        is HomeRow.ContinueWatching -> row.copy(titles = row.titles.distinctBy { it.title.id })
        is HomeRow.Favourites -> row.copy(channels = row.channels.distinctBy { it.cardKey })
        is HomeRow.KidsChannels -> row.copy(channels = row.channels.distinctBy { it.cardKey })
        is HomeRow.Channels -> row.copy(countries = row.countries.distinctBy { it.cardKey })
        is HomeRow.Trending -> row.copy(titles = row.titles.distinctBy { it.id })
        is HomeRow.NewOnYourService -> row.copy(titles = row.titles.distinctBy { it.id })
        is HomeRow.NewForKids -> row.copy(titles = row.titles.distinctBy { it.id })
        is HomeRow.ForKids -> row.copy(titles = row.titles.distinctBy { it.id })
        is HomeRow.Genre -> row.copy(titles = row.titles.distinctBy { it.id })
        is HomeRow.LiveNow -> row.copy(games = row.games.distinctBy { it.cardKey })
        is HomeRow.Today -> row.copy(games = row.games.distinctBy { it.cardKey })
        is HomeRow.LeagueGames -> row.copy(games = row.games.distinctBy { it.cardKey })
        is HomeRow.MoreLiveEvents -> row.copy(events = row.events.distinctBy { it.channel.cardKey })
        is HomeRow.Replays -> row.copy(replays = row.replays.distinctBy { it.cardKey })
    }
}
