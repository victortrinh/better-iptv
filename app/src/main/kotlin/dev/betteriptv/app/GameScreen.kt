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
import dev.betteriptv.app.ui.components.PrimaryAction
import dev.betteriptv.app.ui.components.TitleName
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
 * language, the Viewer's Content languages first, each Feed a heading naming its Event channel or
 * Broadcaster with its variants listed under it as a Channel's Versions are, best first for this
 * stick and TV ([deviceProfile], read when the screen opens). Play starts the best Feed's play plan
 * ([planFor]: [Library.channelPlayPlan], its variants as Backups); choosing a variant row starts the
 * Feed from that variant. With no Feed, Play is disabled and says "No stream found". The Game's state is the
 * schedule's: the page never reads a clock off an Event channel's name, and never shows a score.
 * A finished Game's Replay ([replayFor]: [Library.gameReplay], the Sports replay naming both teams on
 * the Game's day), once the Provider has one, shows under the header as a "Replay" row: its name,
 * its date and its Version ([replayPlanFor]: [Library.replayPlayPlan]), which pressing it plays.
 */
@Composable
fun GameScreen(
    entry: GameFeeds,
    deviceProfile: () -> DeviceProfile,
    planFor: (Channel, DeviceProfile) -> PlayPlan,
    onPlay: (PlayPlan, String) -> Unit,
    replayFor: suspend (Game) -> Replay? = { null },
    replayPlanFor: suspend (Replay) -> PlayPlan? = { null },
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
            onPlay(if (version == null) plan else plan.copy(version = plan.versions.firstOrNull { it.listingId == version.listingId } ?: version), name)
        }
        Unit
    }
    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        GameContent(entry, plans, replay, onPlay = { play(it, null) }, onChoose = play, onPlayReplay = { plan -> onPlay(plan, name) })
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
    val away = rememberArtwork(game.away.logo)
    val home = rememberArtwork(game.home.logo)
    val groups = remember(entry) { entry.feeds.groupBy { it.language } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            Row(Modifier.padding(top = Spacing.xl, bottom = Spacing.xl)) {
                Column(Modifier.width(HEADER_WIDTH)) {
                    TitleName(context.matchup(game), null)
                    Spacer(Modifier.height(Spacing.s))
                    val feeds = if (entry.feeds.isEmpty()) stringResource(R.string.game_no_stream)
                    else context.resources.getQuantityString(R.plurals.game_feeds, entry.feeds.size, entry.feeds.size)
                    Text(
                        listOf(context.leagueName(game.league), context.gameCaption(game), feeds).joinToString(" · "),
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
                // The teams as on the card: away left, home right, whole logos or short names.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                    HeaderLogo(away, game.away.shortName)
                    HeaderLogo(home, game.home.shortName)
                }
            }
        }
        if (replay != null) {
            val (listing, plan) = replay
            item(key = "replay") {
                Text(stringResource(R.string.replay), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp).testTag("replay-title"))
                Column(Modifier.padding(bottom = 2.dp)) {
                    Text(listing.title, style = Type.label, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(context.replayDate(listing, long = true) ?: stringResource(R.string.replay), style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VersionRow(context.versionLine(plan.version), onClick = { onPlayReplay(plan) }, modifier = Modifier.padding(bottom = Spacing.m).testTag("replay"))
            }
        }
        if (entry.feeds.isNotEmpty()) {
            item { Text(stringResource(R.string.feeds_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = 6.dp)) }
            groups.entries.forEachIndexed { index, (language, feeds) ->
                feedGroup(context, language, feeds, plans, first = index == 0, onChoose)
            }
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
 * The Feeds in one [language]: a heading as a Title's language group has ("English · 2"), then each
 * Feed's name with what it is under it, and its variants as Version rows, best first as [plans] rank them.
 */
private fun LazyListScope.feedGroup(
    context: Context,
    language: String?,
    feeds: List<Feed>,
    plans: Map<String, PlayPlan>?,
    first: Boolean,
    onChoose: (Feed, Version) -> Unit,
) {
    item(key = "language $language") {
        Text(
            "${language?.let(::languageName) ?: context.getString(R.string.language_untagged)} · ${feeds.size}",
            style = Type.title,
            color = Colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (first) 0.dp else Spacing.m, bottom = 2.dp),
        )
    }
    feeds.forEachIndexed { feedIndex, feed ->
        item(key = "feed ${feed.channel.key}") {
            Column(Modifier.padding(top = if (feedIndex == 0) 0.dp else Spacing.s, bottom = 2.dp)) {
                Text(feed.name, style = Type.label, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(context.feedKind(feed), style = Type.caption, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Until the plans are in, the Provider's order stands in for the ranked one.
        val versions = plans?.get(feed.channel.key)?.versions
            ?: feed.channel.variants.map { Version(it.id, it.name, VersionAttributes(), liveFormat = "ts") }
        versions.forEachIndexed { index, version ->
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
