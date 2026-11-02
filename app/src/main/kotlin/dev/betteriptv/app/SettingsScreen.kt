package dev.betteriptv.app

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.UpdateState
import dev.betteriptv.core.offered
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Viewer's settings, in a single column: App language, Content languages, Region and Leagues under
 * Languages, the Text size under Display, the Quality cap and whether the next Episode plays by itself under Playback, Sync now
 * ([onSyncNow]) with how the Sync is going and the Title counts under Catalog, the App version under
 * App, which offers the [update] when there is one ([onUpdate]). Content languages, Region
 * and Leagues each open a list in place of the column; Back returns to the row that opened it.
 * [focusTab]: reached by picking its tab, which keeps focus.
 */
@Composable
fun SettingsScreen(
    library: Library,
    settings: ViewerSettings,
    update: UpdateState,
    onChange: ((ViewerSettings) -> ViewerSettings) -> Unit,
    onUpdate: () -> Unit,
    onSyncNow: () -> Unit,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val appLanguageRow = remember { FocusRequester() }
    val contentLanguagesRow = remember { FocusRequester() }
    val regionRow = remember { FocusRequester() }
    val leaguesRow = remember { FocusRequester() }
    var returnTo by rememberSaveable { mutableStateOf<Picker?>(null) }
    val tab = remember { FocusRequester() }
    FocusTabOnArrival(focusTab, tab)

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        BrowseTabs(Browse.Settings, onTab, Modifier.padding(horizontal = Spacing.screenX), selectedFocus = tab)
        when (picking) {
            Picker.ContentLanguages -> {
                BackHandler { picking = null }
                ContentLanguagesList(library, settings.contentLanguages, onChange = { languages -> onChange { it.copy(contentLanguages = languages) } })
            }
            Picker.Region -> {
                BackHandler { picking = null }
                RegionList(settings.region, settings.appLanguage, settings.contentLanguages, onPick = { region ->
                    onChange { it.copy(region = region) }
                    picking = null
                })
            }
            Picker.Leagues -> {
                BackHandler { picking = null }
                LeaguesList(settings.leaguesOn, onChange = { leagues -> onChange { it.copy(leagues = leagues) } })
            }
            null -> LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(COLUMN_WIDTH),
            ) {
                item { Heading(stringResource(R.string.settings_languages)) }
                item {
                    Labelled(stringResource(R.string.settings_app_language), stringResource(R.string.settings_app_language_detail)) {
                        ChoiceRow(
                            APP_LANGUAGES.map(::languageName),
                            selected = APP_LANGUAGES.indexOf(settings.appLanguage),
                            onSelect = { index -> onChange { it.copy(appLanguage = APP_LANGUAGES[index]) } },
                            modifier = Modifier.focusRequester(appLanguageRow),
                        )
                    }
                }
                item {
                    SettingRow(
                        stringResource(R.string.settings_content_languages),
                        settings.contentLanguages.joinToString(", ", transform = ::languageName),
                        onClick = {
                            returnTo = Picker.ContentLanguages
                            picking = Picker.ContentLanguages
                        },
                        Modifier.focusRequester(contentLanguagesRow),
                        detail = stringResource(R.string.settings_content_languages_detail),
                    )
                }
                item {
                    SettingRow(
                        stringResource(R.string.settings_region),
                        countryName(settings.region, settings.appLanguage),
                        onClick = {
                            returnTo = Picker.Region
                            picking = Picker.Region
                        },
                        Modifier.focusRequester(regionRow),
                        detail = stringResource(R.string.settings_region_detail),
                    )
                }
                item {
                    val on = League.entries.filter { it in settings.leaguesOn }
                    SettingRow(
                        stringResource(R.string.settings_leagues),
                        if (on.isEmpty()) stringResource(R.string.settings_leagues_none) else on.map { leagueName(it) }.joinToString(", "),
                        onClick = {
                            returnTo = Picker.Leagues
                            picking = Picker.Leagues
                        },
                        Modifier.focusRequester(leaguesRow),
                        detail = stringResource(R.string.settings_leagues_detail),
                    )
                }
                item { Heading(stringResource(R.string.settings_display)) }
                item {
                    Labelled(stringResource(R.string.settings_text_size), stringResource(R.string.settings_text_size_detail)) {
                        ChoiceRow(
                            listOf(
                                stringResource(R.string.settings_text_size_small),
                                stringResource(R.string.settings_text_size_medium),
                                stringResource(R.string.settings_text_size_large),
                            ),
                            selected = TextSize.entries.indexOf(settings.textSize),
                            onSelect = { index -> onChange { it.copy(textSize = TextSize.entries[index]) } },
                        )
                    }
                }
                item { Heading(stringResource(R.string.settings_playback)) }
                item {
                    Labelled(stringResource(R.string.settings_quality_cap), stringResource(R.string.settings_quality_cap_detail)) {
                        ChoiceRow(
                            listOf("4K", "1080p", "720p"),
                            selected = QualityCap.entries.indexOf(settings.qualityCap),
                            onSelect = { index -> onChange { it.copy(qualityCap = QualityCap.entries[index]) } },
                        )
                    }
                }
                item {
                    Labelled(stringResource(R.string.settings_background_reading), stringResource(R.string.settings_background_reading_detail)) {
                        ChoiceRow(
                            listOf(
                                stringResource(R.string.settings_background_reading_off),
                                stringResource(R.string.settings_background_reading_gentle),
                                stringResource(R.string.settings_background_reading_normal),
                            ),
                            selected = BackgroundReading.entries.indexOf(settings.backgroundReading),
                            onSelect = { index -> onChange { it.copy(backgroundReading = BackgroundReading.entries[index]) } },
                        )
                    }
                }
                item {
                    Labelled(stringResource(R.string.settings_play_next), stringResource(R.string.settings_play_next_detail)) {
                        ChoiceRow(
                            listOf(stringResource(R.string.settings_on), stringResource(R.string.settings_off)),
                            selected = if (settings.playNextAutomatically) 0 else 1,
                            onSelect = { index -> onChange { it.copy(playNextAutomatically = index == 0) } },
                        )
                    }
                }
                item { Heading(stringResource(R.string.settings_catalog)) }
                item { SyncRow(library, onSyncNow) }
                item { Heading(stringResource(R.string.settings_app)) }
                item {
                    val offered = update.offered
                    SettingRow(
                        stringResource(R.string.settings_version),
                        when {
                            offered != null -> stringResource(R.string.settings_update_to, offered.versionName)
                            update == UpdateState.UpToDate -> stringResource(R.string.settings_up_to_date, BuildConfig.VERSION_NAME)
                            else -> BuildConfig.VERSION_NAME
                        },
                        onClick = { if (offered != null) onUpdate() },
                    )
                }
            }
        }
    }
    if (picking == null) {
        LaunchedEffect(Unit) {
            // After the App language recreates the screen, the rows aren't placed on the first frame yet.
            withFrameNanos {}
            when (returnTo) {
                Picker.ContentLanguages -> contentLanguagesRow
                Picker.Region -> regionRow
                Picker.Leagues -> leaguesRow
                // Reached by its tab, which keeps focus.
                null -> appLanguageRow.takeUnless { focusTab }
            }?.requestFocus()
        }
    }
}

/** Sync now, with how the Sync is going as its value (in the error colour when it failed) and the Title counts under it. */
@Composable
private fun SyncRow(library: Library, onSyncNow: () -> Unit) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val counts by produceState<Pair<Int, Int>?>(null, status.last) {
        value = withContext(Dispatchers.IO) { library.titleCount(TitleKind.Movie) to library.titleCount(TitleKind.Show) }
    }
    val context = LocalContext.current
    SettingRow(
        stringResource(R.string.sync_now),
        context.describe(status),
        onClick = onSyncNow,
        detail = counts?.let { (movies, shows) -> context.catalogCounts(movies, shows) },
        enabled = status !is SyncStatus.Running && status !is SyncStatus.Matching,
        valueColor = if (status is SyncStatus.Failed) Colors.error else Colors.inkMuted,
        valueLines = 2,
    )
}

private enum class Picker { ContentLanguages, Region, Leagues }

/**
 * The catalog's languages to turn on and off as Content languages, the chosen ones first in the
 * order they were chosen: the first one the Title has is the one Play picks. The last one can't be
 * turned off.
 */
@Composable
private fun ContentLanguagesList(library: Library, chosen: List<String>, onChange: (List<String>) -> Unit) {
    val catalog by produceState(emptyList<String>()) { value = library.catalogLanguages() }
    // Kept from opening, so a language turned off doesn't jump away from under focus.
    val order = remember(catalog) { (chosen + catalog).distinct() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(COLUMN_WIDTH),
    ) {
        item { Heading(stringResource(R.string.settings_content_languages)) }
        items(order, key = { it }) { language ->
            val isChosen = language in chosen
            SettingRow(
                languageName(language),
                if (isChosen) CHOSEN else "",
                onClick = {
                    if (!isChosen) onChange(chosen + language)
                    else if (chosen.size > 1) onChange(chosen - language)
                },
                if (language == order.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/**
 * The fifteen Leagues offered, in [League]'s order, the ones [on] marked: OK turns a League on or
 * off. Every one can be off; the Live tab then shows no Games.
 */
@Composable
private fun LeaguesList(on: Set<League>, onChange: (Set<League>) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(COLUMN_WIDTH),
    ) {
        item { Heading(stringResource(R.string.settings_leagues)) }
        items(League.entries, key = { it.key }) { league ->
            val isOn = league in on
            SettingRow(
                leagueName(league),
                if (isOn) CHOSEN else "",
                onClick = { onChange(if (isOn) on - league else on + league) },
                if (league == League.entries.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/** A League's name in the App language: the acronym the Region knows it by, or its name where it has none. */
@Composable
private fun leagueName(league: League): String = stringResource(
    when (league) {
        League.Nhl -> R.string.league_nhl
        League.Nfl -> R.string.league_nfl
        League.Nba -> R.string.league_nba
        League.Mlb -> R.string.league_mlb
        League.Cfl -> R.string.league_cfl
        League.Mls -> R.string.league_mls
        League.PremierLeague -> R.string.league_premier_league
        League.ChampionsLeague -> R.string.league_champions_league
        League.LaLiga -> R.string.league_la_liga
        League.Bundesliga -> R.string.league_bundesliga
        League.SerieA -> R.string.league_serie_a
        League.Ligue1 -> R.string.league_ligue_1
        League.NcaaFootball -> R.string.league_ncaa_football
        League.NcaaMensBasketball -> R.string.league_ncaa_mens_basketball
        League.Wnba -> R.string.league_wnba
    },
)

/**
 * Every country, by its name in the [appLanguage]: the [suggestedRegions] under Suggested, the
 * Viewer's [region] first, then All countries beside an A–Z rail. Left from a country moves to its
 * letter on the rail; OK or Right on a letter jumps to that letter's first country.
 */
@Composable
private fun RegionList(region: String, appLanguage: String, contentLanguages: List<String>, onPick: (String) -> Unit) {
    val countries = remember(appLanguage) {
        val collator = Collator.getInstance(Locale.forLanguageTag(appLanguage))
        Locale.getISOCountries().sortedWith { a, b -> collator.compare(countryName(a, appLanguage), countryName(b, appLanguage)) }
    }
    // The system's country, not the app's: the app's locale follows the App language.
    val suggested = remember(region, contentLanguages) { suggestedRegions(region, Resources.getSystem().configuration.locales[0].country, contentLanguages) }
    val letterOf = remember(countries) { countries.associateWith { indexLetter(countryName(it, appLanguage)) } }
    // Sorted by the App language's collator, so a letter's countries follow one another.
    val firstOfLetter = remember(countries) { countries.reversed().associateBy { letterOf.getValue(it) }.toSortedMap() }
    val rows = remember(countries) { countries.associateWith { FocusRequester() } }
    val letters = remember(firstOfLetter) { firstOfLetter.keys.associateWith { FocusRequester() } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun jump(letter: Char) {
        val country = firstOfLetter.getValue(letter)
        scope.launch {
            // The headings and Suggested come before All countries.
            list.scrollToItem(2 + suggested.size + countries.indexOf(country))
            withFrameNanos {}
            rows.getValue(country).requestFocus()
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Row(Modifier.padding(start = Spacing.screenX)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((letter, focus) in letters) {
                LetterKey(letter, onClick = { jump(letter) }, Modifier.focusRequester(focus))
            }
        }
        Spacer(Modifier.width(16.dp))
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(end = Spacing.screenX, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(COLUMN_WIDTH),
        ) {
            @Composable
            fun Country(country: String, modifier: Modifier) {
                SettingRow(
                    countryName(country, appLanguage),
                    if (country == region) CHOSEN else "",
                    onClick = { onPick(country) },
                    modifier.focusProperties { left = letters.getValue(letterOf.getValue(country)) },
                )
            }
            item { Heading(stringResource(R.string.settings_region_suggested)) }
            items(suggested, key = { "suggested:$it" }) { country ->
                Country(country, if (country == region) Modifier.focusRequester(first) else Modifier)
            }
            item { Heading(stringResource(R.string.settings_region_all)) }
            items(countries, key = { it }) { country -> Country(country, Modifier.focusRequester(rows.getValue(country))) }
        }
    }
}

/** A letter on the Region list's rail; Right, like OK, jumps to its countries. */
@Composable
private fun LetterKey(letter: Char, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick,
        modifier.size(LETTER).onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) onClick()
            event.key == Key.DirectionRight
        },
        weight = FocusWeight.Control,
        corner = LETTER / 2,
        focusedContainer = Colors.focus,
    ) { focused ->
        Text(
            letter.toString(),
            style = Type.label,
            color = if (focused) Colors.onFocus else Colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

internal fun countryName(code: String, appLanguage: String): String = Locale("", code).getDisplayCountry(Locale.forLanguageTag(appLanguage))

@Composable
private fun Heading(text: String) {
    Text(text, style = Type.headline, color = Colors.ink, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** An exclusive setting: its label and detail in the label column, the choices beside them. */
@Composable
private fun Labelled(label: String, detail: String, content: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.width(320.dp)) {
            Text(label, style = Type.label, color = Colors.ink)
            Text(detail, style = Type.caption, color = Colors.inkMuted)
        }
        Spacer(Modifier.width(16.dp))
        content()
    }
    Spacer(Modifier.height(2.dp))
}

private val COLUMN_WIDTH = 760.dp

private val LETTER = 36.dp

/** The value of a chosen row in a list. */
private const val CHOSEN = "✓"
