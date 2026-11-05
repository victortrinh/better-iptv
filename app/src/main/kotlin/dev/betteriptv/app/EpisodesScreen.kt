package dev.betteriptv.app

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeStill
import dev.betteriptv.core.Season
import dev.betteriptv.core.ShowEpisodes
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Episode picker (DESIGN.md, Details): a Show's Title page's second level, opened by its
 * Episodes action, over the same backdrop under a `ground` veil at 85% ([Colors.veilDeep]), laid
 * out like the Channel browser. The seasons are in the left column, the picked season's Episodes on
 * the right, each with its TMDB still (asked for the season once focus rests on it, [stillsOf], and
 * loaded for the rows on screen once they settle, fading in), its name, its runtime, how far the
 * Viewer got in it, and its languages only when they differ from the Show's usual set
 * ([ShowEpisodes.languagesNamed]). One not out yet says when it will be, and can't be chosen.
 *
 * It opens on the Episode to watch next ([ShowEpisodes.next]), in its season. Up and Down in the
 * column pick another season, whose Episodes show from their top; Right goes to the Episode focused
 * last in the list, else the season's next one, else its first; Left from any Episode returns to its
 * season. OK on an Episode plays it ([onEpisode]). Back is the caller's, and returns to the Episodes action.
 */
@Composable
internal fun EpisodesContent(
    showName: String,
    episodes: ShowEpisodes,
    onEpisode: (Episode) -> Unit,
    stillsOf: suspend (Int) -> Map<Int, EpisodeStill> = { emptyMap() },
) {
    val context = LocalContext.current
    val seasons = episodes.seasons
    val next = episodes.next
    // By number, so the Episodes read again (a Header read Confirming something) keep the season picked.
    var picked by remember { mutableStateOf(next?.season) }
    val shown = seasons.firstOrNull { it.number == picked } ?: seasons.firstOrNull()
    // The Episode focused last in the list, which Right from the column returns to; none once the list is another season's.
    var lastFocused by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()
    val column = rememberLazyListState()
    val seasonFocus = remember(seasons) { seasons.associate { it.number to FocusRequester() } }
    val entry = remember { FocusRequester() }
    val entryNumber = lastFocused?.takeIf { number -> shown?.episodes?.any { it.number == number } == true }
        ?: next?.takeIf { it.season == shown?.number }?.number
        ?: shown?.episodes?.firstOrNull()?.number
    // A season's stills are asked for once focus rests on it, so moving through the seasons asks for nothing.
    var stills by remember { mutableStateOf<Map<Int, EpisodeStill>>(emptyMap()) }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(shown?.number) {
        val season = shown?.number ?: return@LaunchedEffect
        if (arrived) {
            list.requestScrollToItem(0)
            lastFocused = null
        }
        stills = emptyMap()
        delay(SETTLE_MILLIS)
        stills = stillsOf(season)
    }
    // It opens on the Episode to watch next, in its season, the one before it peeking above.
    LaunchedEffect(episodes) {
        if (arrived) return@LaunchedEffect
        val season = shown ?: return@LaunchedEffect
        column.requestScrollToItem(seasons.indexOf(season))
        val at = season.episodes.indexOfFirst { it.number == entryNumber }
        list.requestScrollToItem((at - 1).coerceAtLeast(0))
        withFrameNanos {}
        runCatching { entry.requestFocus() }.onFailure { seasonFocus[season.number]?.requestFocus() }
        arrived = true
    }

    Box(Modifier.fillMaxSize().background(Colors.veilDeep).testTag("episodes")) {
        Column(Modifier.fillMaxSize().padding(top = Spacing.screenY)) {
            Column(Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.xl)) {
                Text(showName, style = Type.title, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.episodes_title), style = Type.headline, color = Colors.ink)
                if (episodes.unreadVersions > 0) {
                    Text(
                        context.resources.getQuantityString(R.plurals.episodes_unread, episodes.unreadVersions, episodes.unreadVersions),
                        style = Type.caption,
                        color = Colors.inkMuted,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.l))
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(SEASONS_WIDTH + Spacing.screenX),
                    state = column,
                    contentPadding = PaddingValues(start = Spacing.screenX, bottom = Spacing.screenY),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(seasons, key = { it.number }) { season ->
                        SeasonRow(
                            context.seasonName(season.number),
                            season.episodes.size,
                            picked = season.number == shown?.number,
                            // OK goes on into its Episodes, as Right does.
                            onClick = { if (entryNumber != null) entry.requestFocus() },
                            modifier = Modifier
                                .focusRequester(seasonFocus.getValue(season.number))
                                .focusProperties { right = if (entryNumber == null) FocusRequester.Cancel else entry }
                                .onFocusChanged { if (it.isFocused) picked = season.number },
                        )
                    }
                }
                Spacer(Modifier.width(40.dp))
                LazyColumn(
                    Modifier.weight(1f),
                    state = list,
                    contentPadding = PaddingValues(end = Spacing.screenX, bottom = Spacing.screenY),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(shown?.episodes.orEmpty(), key = { "${it.season} ${it.number}" }) { episode ->
                        val toSeason = seasonFocus[episode.season]
                        var modifier = Modifier
                            .focusProperties { if (toSeason != null) left = toSeason }
                            .onFocusChanged { if (it.isFocused) lastFocused = episode.number }
                        if (episode.number == entryNumber) modifier = modifier.focusRequester(entry)
                        EpisodeRow(
                            episode,
                            still = stills[episode.number],
                            languages = episodes.languagesNamed(episode),
                            onClick = { onEpisode(episode) },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}

/** "Season 2", or "Specials" for season 0, in the App language. */
internal fun Context.seasonName(number: Int): String =
    if (number == 0) getString(R.string.season_specials) else getString(R.string.season_title, number)

/**
 * The Episodes action's value on a Show's Title page: where the Viewer is ("Season 2 · E4", the
 * Episode to watch next), or, when they never played the Show, the season it starts in and its
 * Episode count ("Season 1 · 14 Episodes").
 */
internal fun Context.episodesValue(episodes: ShowEpisodes): String? {
    val next = episodes.next ?: return null
    val season = episodes.seasons.firstOrNull { it.number == next.season } ?: return null
    val started = episodes.seasons.any { s -> s.episodes.any { it.progress != null } }
    return if (started) getString(R.string.episodes_at, seasonName(next.season), next.number)
    else "${seasonName(next.season)} · ${resources.getQuantityString(R.plurals.episodes_count, season.episodes.size, season.episodes.size)}"
}

/**
 * A season in the picker's column: its name on the left, its Episode count on the right in muted
 * detail. The [picked] one, whose Episodes are listed, sits on stronger glass while focus is in the
 * list, as the Channel browser's picked Category does.
 */
@Composable
private fun SeasonRow(name: String, count: Int, picked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick,
        modifier.fillMaxWidth(),
        weight = FocusWeight.Row,
        container = if (picked) Colors.surfaceHigh else Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Type.label, color = if (focused) Colors.onFocus else Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.m))
            Text(count.toString(), style = Type.detail, color = if (focused) Colors.onFocus else Colors.inkMuted, maxLines = 1)
        }
    }
}

/**
 * An Episode in the picker's list: its [still] on a small glass tile (fading in once loaded), its
 * number and name in label type, then in muted detail its runtime and, when they differ from the
 * Show's usual set, its [languages]; or, for one not out yet, when it will be ("Available October
 * 31"), and the row is disabled. A started Episode shows its progress bar under its name, full once Watched.
 */
@Composable
private fun EpisodeRow(episode: Episode, still: EpisodeStill?, languages: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val available = episode.availableOn
    val enabled = available == null
    FocusSurface(
        { if (enabled) onClick() },
        modifier.fillMaxWidth(),
        weight = FocusWeight.Row,
        container = Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val image = rememberArtwork(still?.still, card = true)
            Box(
                Modifier.width(STILL_WIDTH).aspectRatio(16f / 9f).clip(RoundedCornerShape(Corners.badge))
                    .background(if (focused) Colors.onFocus.copy(alpha = 0.08f) else Colors.surface),
            ) {
                if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                val ink = when {
                    focused -> Colors.onFocus
                    enabled -> Colors.ink
                    else -> Colors.inkMuted
                }
                Text(
                    episode.name?.let { context.getString(R.string.episode_named, episode.number, it) } ?: context.getString(R.string.episode_number, episode.number),
                    style = Type.label,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = if (available != null) context.getString(R.string.episode_available, context.monthDay(available))
                else listOfNotNull(still?.runtimeMinutes?.let(context::runtime), languages.joinToString(", ") { languageName(it) }.ifEmpty { null }).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = Type.detail,
                        color = if (focused) Colors.onFocus.copy(alpha = 0.7f) else Colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val progress = episode.progress
                if (progress != null) {
                    val bar = if (focused) Colors.onFocus else Colors.ink
                    Box(Modifier.padding(top = 6.dp).width(PROGRESS_WIDTH).height(4.dp).background(bar.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(bar, RoundedCornerShape(50)))
                    }
                }
            }
        }
    }
}

/** "October 31" in the App language, with the year when it isn't this one. */
internal fun Context.monthDay(date: LocalDate): String {
    val locale = resources.configuration.locales[0]
    val skeleton = if (date.year == LocalDate.now().year) "MMMMd" else "MMMMdyyyy"
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/** The column of seasons: the Channel browser's Category column's width. */
private val SEASONS_WIDTH = 290.dp

/** An Episode's still: 16:9, about a third of a Continue watching still. */
private val STILL_WIDTH = 112.dp

/** An Episode's progress bar: a Setting row's. */
private val PROGRESS_WIDTH = 120.dp
