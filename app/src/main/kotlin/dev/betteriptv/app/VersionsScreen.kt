package dev.betteriptv.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import dev.betteriptv.app.ui.components.PulsingDot
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.CertaintyLegend
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionColumn
import dev.betteriptv.core.VersionTableGroup
import dev.betteriptv.core.versionTable
import kotlinx.coroutines.launch

/**
 * What a Versions screen lists: the Versions of a Movie, or of the Episode a Show's Play starts,
 * grouped by language, the Viewer's [languages] first and the [otherLanguages] behind a row of their
 * own. [heading] names what they're Versions of, over the "Versions" headline: the Title, or the
 * Show and its Episode ("Breaking Bad · S1 E6"). With the Viewer's [contentLanguages] and the Title's
 * [originalLanguage] (when Prefer original audio acts on it), a Version whose audio is in one other
 * language says so ("German only").
 */
internal data class VersionsList(
    val heading: String,
    val languages: List<LanguageGroup>,
    val otherLanguages: List<LanguageGroup>,
    val contentLanguages: List<String> = emptyList(),
    val originalLanguage: String? = null,
) {
    /** Every Version, Backups included. */
    val count: Int get() = (languages + otherLanguages).sumOf { it.versionCount }
}

/**
 * The Versions screen (DESIGN.md, Details): the Title page's second level, opened by its Versions
 * action, over the same backdrop under a `ground` veil at 85% ([Colors.veilDeep]). It holds the
 * Confirmed/Likely legend, the Version table ([versionTable]: empty cells left blank, an Audio cell
 * only where it says more than its language heading, columns with nothing in them hidden, Backups
 * under their Version in muted type), Other languages, the [cast] and [directors] the Provider names
 * and, with TMDB's art on the page, its notice.
 * Focus opens on the first Version; choosing a row plays that one ([onChoose]). Back is the
 * caller's, and returns to the Versions action. While Header reads are [checking] Versions, the
 * legend says so ([CheckingVersions]).
 */
@Composable
internal fun VersionsContent(versions: VersionsList, cast: List<String>, directors: List<String>, fromTmdb: Boolean, onChoose: (Version) -> Unit, checking: Boolean = false) {
    val context = LocalContext.current
    var showOthers by rememberSaveable(versions.heading) { mutableStateOf(false) }
    // With no Version in the Viewer's languages, the other languages are all there is to show.
    val shown = versions.languages.ifEmpty { versions.otherLanguages }
    val hidden = if (versions.languages.isEmpty()) emptyList() else versions.otherLanguages
    // Over every group it may show, so opening Other languages never moves a column.
    val table = remember(versions) { versionTable(shown + hidden, versions.contentLanguages, versions.originalLanguage) }
    val firstVersion = remember { FocusRequester() }
    LaunchedEffect(versions.heading) { runCatching { firstVersion.requestFocus() } }
    // The cast and TMDB's notice take no focus, so the last row brings them into view when it's focused.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lastKey: Any? = when {
        hidden.isNotEmpty() && !showOthers -> OTHER_LANGUAGES
        else -> table.groups.take(if (showOthers) table.groups.size else shown.size).lastOrNull()?.rows?.lastOrNull()?.version?.listingId
    }
    val toEnd = Modifier.onFocusChanged { focus ->
        if (focus.isFocused) scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
    }

    Box(Modifier.fillMaxSize().background(Colors.veilDeep)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("versions"),
            state = listState,
            contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item(key = "headline") {
                Row(Modifier.padding(top = Spacing.xl, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(versions.heading, style = Type.title, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.versions_title), style = Type.headline, color = Colors.ink)
                    }
                    Spacer(Modifier.width(Spacing.l))
                    CheckingVersions(checking, Modifier.padding(end = Spacing.xl))
                    CertaintyLegend()
                }
            }
            item(key = "columns") { VersionHeader(Modifier.padding(bottom = 2.dp), columns = table.columns) }
            table.groups.take(shown.size).forEachIndexed { index, group ->
                languageGroup(context, group, table.columns, first = index == 0, firstVersion, onChoose, lastKey, toEnd)
            }
            if (hidden.isNotEmpty()) {
                // Other languages open under this row, so it keeps focus where it is.
                item(key = OTHER_LANGUAGES) {
                    SettingRow(
                        stringResource(R.string.other_languages),
                        if (showOthers) stringResource(R.string.other_languages_hide) else stringResource(R.string.other_languages_show, hidden.sumOf { it.versionCount }),
                        onClick = { showOthers = !showOthers },
                        modifier = Modifier.padding(top = Spacing.m).then(if (lastKey == OTHER_LANGUAGES) toEnd else Modifier),
                        detail = hidden.joinToString(", ") { group -> group.language?.let(::languageName).orEmpty() },
                    )
                }
                if (showOthers) table.groups.drop(shown.size).forEach { languageGroup(context, it, table.columns, first = false, firstVersion, onChoose, lastKey, toEnd) }
            }
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = Spacing.xl)) {
                        Text(stringResource(R.string.cast_title), style = Type.title, color = Colors.ink)
                        Text(cast.joinToString(", "), style = Type.body, color = Colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
            if (directors.isNotEmpty()) {
                item(key = "directors") {
                    Column(Modifier.padding(top = if (cast.isEmpty()) Spacing.xl else Spacing.m)) {
                        Text(pluralStringResource(R.plurals.director_title, directors.size), style = Type.title, color = Colors.ink)
                        Text(directors.joinToString(", "), style = Type.body, color = Colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
            if (fromTmdb) {
                // TMDB's terms ask for this notice wherever its data is shown.
                item(key = "attribution") {
                    Text(stringResource(R.string.tmdb_attribution), style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(top = Spacing.xl))
                }
            }
        }
    }
}

/** The Other languages row's key in the list. */
private const val OTHER_LANGUAGES = "other languages"

/**
 * A language's heading ("English · 3"), or "More versions · 2" over the Versions whose language
 * nothing names, then its rows: each Version, its Backups under it.
 */
private fun LazyListScope.languageGroup(
    context: Context,
    group: VersionTableGroup,
    columns: List<VersionColumn>,
    first: Boolean,
    firstVersion: FocusRequester,
    onChoose: (Version) -> Unit,
    lastKey: Any?,
    toEnd: Modifier,
) {
    item(key = "language ${group.language}") {
        Text(
            "${group.language?.let(::languageName) ?: context.getString(R.string.versions_untagged)} · ${group.versionCount}",
            style = Type.title,
            color = Colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (first) 0.dp else Spacing.m, bottom = 2.dp),
        )
    }
    group.rows.forEachIndexed { index, row ->
        item(key = row.version.listingId) {
            VersionRow(
                context.versionLine(row),
                // A Backup plays itself first: the Viewer chose it for what its name doesn't say (burned-in subtitles).
                onClick = { onChoose(row.version) },
                modifier = (if (first && index == 0) Modifier.focusRequester(firstVersion) else Modifier)
                    .then(if (row.version.listingId == lastKey) toEnd else Modifier),
                columns = columns,
            )
        }
    }
}

/**
 * "Checking Versions…" in muted caption after a 6 dp [PulsingDot] (DESIGN.md, Details), while the
 * Title page's Header reads are [checking] Versions (#457): what they Confirm changes badges and
 * values in place, and this says why. No count, since a Show's can run to hundreds. Checking that
 * starts after it is composed waits a beat ([APPEAR_AFTER]) so a single quick read doesn't flash;
 * checking already under way (the Versions screen opened mid-check) shows at once. It fades out
 * ([FADE_MILLIS]). It takes no focus and nothing moves for it.
 */
@Composable
internal fun CheckingVersions(checking: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(checking) }
    LaunchedEffect(checking) {
        if (checking && !visible) delay(APPEAR_AFTER)
        visible = checking
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(FADE_MILLIS), label = "checking")
    if (!visible && alpha == 0f) return
    Row(modifier.graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(6.dp)
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.versions_checking), style = Type.caption, color = Colors.inkMuted, maxLines = 1)
    }
}

private const val APPEAR_AFTER = 1_000L
private const val FADE_MILLIS = 300
