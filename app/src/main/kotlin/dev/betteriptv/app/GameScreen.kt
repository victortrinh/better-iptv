package dev.betteriptv.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.BackdropScrims
import dev.betteriptv.app.ui.components.PrimaryAction
import dev.betteriptv.app.ui.components.TeamField
import dev.betteriptv.app.ui.components.teamTint
import dev.betteriptv.app.ui.components.TitleName
import dev.betteriptv.app.ui.components.VersionLine
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Channel
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Feed
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Replay
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Game's page, built from the Title page's parts: a header naming the League, both teams and
 * "Live" or the start time, Play with the best Feed named under it, then every Feed grouped by
 * language, the Viewer's Content languages first, each Feed a heading naming its Event channel,
 * Broadcaster or League network by its service and broadcast ([feedName]), with its variants listed
 * under it as a Channel's Versions are, best first for this stick and TV ([deviceProfile], read when
 * the screen opens), or as one row when it has one variant; the Feeds marked "Not
 * this game" on this device come last, in a group of their own, each marked. Play starts the best
 * Feed's play plan ([planFor]: [Library.channelPlayPlan], its variants as Backups); choosing a
 * variant row starts the Feed from that variant; either way [onPlay] gets the plan, the Game's name
 * and the Feed, so the player's menu key can list the others. With no Feed, or none left, Play is
 * disabled and says "No stream found". The Game's state is the schedule's: the page never reads a
 * clock off an Event channel's name, and never shows a score. Behind it, the teams' colours fill the
 * screen, away left and home right ([TeamField]), under the [BackdropScrims]; a Game without them
 * keeps the bare ground.
 * A finished Game's Replay ([replayFor]: [Library.gameReplay], the Sports replay naming both teams on
 * the Game's day, for a Game that is over), once the Provider has one, shows under the header as a
 * "Replay" row: its name, its date and its Version ([replayPlanFor]: [Library.replayPlayPlan]), which
 * pressing it plays through [onPlayReplay] with the Game's name (a Replay is no Feed).
 */
@Composable
fun GameScreen(
    entry: GameFeeds,
    deviceProfile: () -> DeviceProfile,
    planFor: (Channel, DeviceProfile) -> PlayPlan,
    onPlay: (PlayPlan, String, Feed) -> Unit,
    replayFor: suspend (Game) -> Replay? = { null },
    replayPlanFor: suspend (Replay) -> PlayPlan? = { null },
    onPlayReplay: (PlayPlan, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // Each Feed's plan, so its rows list its variants in the order watching tries them.
    val plans by produceState<Map<String, PlayPlan>?>(null, entry) {
        val device = withContext(Dispatchers.Default) { deviceProfile() }
        value = withContext(Dispatchers.Default) { entry.feeds.associate { it.channel.key to planFor(it.channel, device) } }
    }
    // The Game's Replay with what playing it starts, once the catalog has one.
    val replay by produceState<Pair<Replay, PlayPlan>?>(null, entry) {
        value = withContext(Dispatchers.Default) { replayFor(entry.game)?.let { replay -> replayPlanFor(replay)?.let { replay to it } } }
    }
    val scope = rememberCoroutineScope()
    val name = remember(entry) { context.matchup(entry.game) }
    val play = { feed: Feed, version: Version? ->
        scope.launch {
            val plan = plans?.get(feed.channel.key) ?: withContext(Dispatchers.Default) { planFor(feed.channel, deviceProfile()) }
            onPlay(if (version == null) plan else plan.copy(version = plan.versions.firstOrNull { it.listingId == version.listingId } ?: version), name, feed)
        }
        Unit
    }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        // The teams' colours, away left and home right, are the page's backdrop, under the usual scrims; without them, the bare ground.
        val awayTint = remember(entry) { entry.game.away?.let { teamTint(it.color, it.alternateColor) } }
        val homeTint = remember(entry) { entry.game.home?.let { teamTint(it.color, it.alternateColor) } }
        if (awayTint != null || homeTint != null) {
            TeamField(awayTint, homeTint, Modifier.fillMaxSize())
            BackdropScrims()
        }
        GameContent(entry, plans, replay, onPlay = { play(it, null) }, onChoose = play, onPlayReplay = { plan -> onPlayReplay(plan, name) })
    }
}

@Composable
private fun GameContent(
    entry: GameFeeds,
    plans: Map<String, PlayPlan>?,
    replay: Pair<Replay, PlayPlan>?,
    onPlay: (Feed) -> Unit,
    onChoose: (Feed, Version) -> Unit,
    onPlayReplay: (PlayPlan) -> Unit,
) {
    val context = LocalContext.current
    val game = entry.game
    val best = entry.best
    val playFocus = remember { FocusRequester() }
    // Play takes first focus, once the list has laid it out; with no Feed to start it is disabled and still takes it, so the Viewer sees why.
    LaunchedEffect(entry.key) {
        withFrameNanos {}
        playFocus.requestFocus()
    }
    val fighters = game.fight?.fighters
    val away = rememberArtwork(fighters?.first()?.headshot ?: game.away?.logo)
    val home = rememberArtwork(fighters?.last()?.headshot ?: game.home?.logo)
    val leagueLogo = rememberArtwork(game.event?.logo)
    // The Feeds by language, as ranked; the dropped ones after every language, as one group.
    val groups = remember(entry) { entry.feeds.filter { !it.dropped }.groupBy { it.language } }
    val dropped = remember(entry) { entry.feeds.filter { it.dropped } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            Row(Modifier.padding(top = Spacing.xl, bottom = Spacing.xl)) {
                Column(Modifier.width(HEADER_WIDTH)) {
                    TitleName(context.matchup(game))
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        listOfNotNull(context.gameLeague(game), context.gameCaption(game), context.mainCardCaption(game), context.feedCount(entry)).joinToString(" · "),
                        style = Type.body,
                        color = Colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("game-header"),
                    )
                    Text(context.matchup(game, short = false), style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
                    Spacer(Modifier.height(Spacing.l))
                    Column(Modifier.width(ACTIONS_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PrimaryAction(
                            stringResource(R.string.play),
                            Icons.Filled.PlayArrow,
                            onClick = { best?.let(onPlay) },
                            modifier = Modifier.focusRequester(playFocus).testTag("play"),
                            enabled = best != null,
                        )
                    }
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        best?.let { context.feedLabel(it) } ?: stringResource(R.string.game_no_stream),
                        style = Type.label,
                        color = Colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("play-label"),
                    )
                }
                Spacer(Modifier.weight(1f))
                // The teams as on the card: away left, home right, whole logos or short names; an event League's logo, or its name; a card's fighters' headshots, or their surnames.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                    if (game.event != null) {
                        HeaderLogo(leagueLogo, context.leagueName(game.league))
                    } else if (fighters != null) {
                        HeaderLogo(away, fighters.first().shortName)
                        HeaderLogo(home, fighters.last().shortName)
                    } else {
                        HeaderLogo(away, game.away?.shortName.orEmpty())
                        HeaderLogo(home, game.home?.shortName.orEmpty())
                    }
                }
            }
        }
        if (replay != null) {
            val (listing, plan) = replay
            item(key = "replay") {
                Text(stringResource(R.string.replay), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp).testTag("replay-title"))
                Column(Modifier.padding(bottom = 2.dp)) {
                    Text(listing.heading, style = Type.label, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(context.replayDate(listing, long = true) ?: stringResource(R.string.replay), style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VersionRow(context.versionLine(plan.version), onClick = { onPlayReplay(plan) }, modifier = Modifier.padding(bottom = Spacing.m).testTag("replay"))
            }
        }
        if (entry.feeds.isNotEmpty()) {
            item { Text(stringResource(R.string.feeds_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp)) }
            groups.entries.forEachIndexed { index, (language, feeds) ->
                feedGroup(context, language?.let(::languageName) ?: context.getString(R.string.language_untagged), "language $language", feeds, plans, first = index == 0, onChoose)
            }
            if (dropped.isNotEmpty()) feedGroup(context, context.getString(R.string.feed_not_this_game), "dropped", dropped, plans, first = groups.isEmpty(), onChoose)
        }
    }
}

@Composable
private fun HeaderLogo(logo: androidx.compose.ui.graphics.painter.Painter?, name: String) {
    Box(Modifier.size(HEADER_LOGO), contentAlignment = Alignment.Center) {
        if (logo != null) Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(name, style = Type.title, color = Colors.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One group of Feeds, a language's or the dropped ones: a [heading] as a Title's language group has
 * ("English · 2", "Not this game · 1"), then each Feed's name with what it is under it ([feedKind],
 * which marks a dropped one), and its variants as Version rows, best first as [plans] rank them.
 */
private fun LazyListScope.feedGroup(
    context: Context,
    heading: String,
    key: String,
    feeds: List<Feed>,
    plans: Map<String, PlayPlan>?,
    first: Boolean,
    onChoose: (Feed, Version) -> Unit,
) {
    item(key = key) {
        Text(
            "$heading · ${feeds.size}",
            style = Type.title,
            color = Colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (first) 0.dp else Spacing.m, bottom = 2.dp),
        )
    }
    feeds.forEachIndexed { feedIndex, feed ->
        // Until the plans are in, the Provider's order stands in for the ranked one.
        val versions = plans?.get(feed.channel.key)?.versions
            ?: feed.channel.variants.map { Version(it.id, it.name, VersionAttributes(), liveFormat = "ts") }
        val top = if (feedIndex == 0) 0.dp else Spacing.s
        // A Feed with one variant is one row, named by the Feed; only several nest under it, each named as the Provider lists it.
        val single = versions.singleOrNull()
        if (single != null) {
            item(key = "feed ${feed.channel.key}") {
                VersionRow(
                    VersionLine(emptyList(), "", "", "", bitrate = null, backup = null, note = context.feedKind(feed), live = context.feedName(feed)),
                    onClick = { onChoose(feed, single) },
                    modifier = Modifier.padding(top = top).testTag("variant ${single.listingId}"),
                )
            }
            return@forEachIndexed
        }
        item(key = "feed ${feed.channel.key}") {
            Column(Modifier.padding(top = top, bottom = 2.dp).testTag("feed ${feed.channel.key}")) {
                Text(context.feedName(feed), style = Type.label, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(context.feedKind(feed), style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        versions.forEach { version ->
            item(key = "variant ${feed.channel.key} ${version.listingId}") {
                VersionRow(
                    context.versionLine(version),
                    onClick = { onChoose(feed, version) },
                    modifier = Modifier.testTag("variant ${version.listingId}"),
                )
            }
        }
    }
}

/** The header's text column, as a Title's. */
private val HEADER_WIDTH = 470.dp

/** The action list's width, as a Title's. */
private val ACTIONS_WIDTH = 360.dp

/** A team's logo in the header, where a Title's poster would sit. */
private val HEADER_LOGO = 120.dp
