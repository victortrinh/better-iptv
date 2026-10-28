package dev.betteriptv.app

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.UpdateState
import dev.betteriptv.core.offered
import java.text.Collator
import java.util.Locale

/**
 * The Viewer's settings, in a single column: App language, Content languages and Region under
 * Languages, the Quality cap under Playback, the App version under App, which offers the [update]
 * when there is one ([onUpdate]). Content languages and Region each open a list in place of the
 * column; Back returns to the row that opened it.
 */
@Composable
fun SettingsScreen(
    library: Library,
    settings: ViewerSettings,
    update: UpdateState,
    onChange: ((ViewerSettings) -> ViewerSettings) -> Unit,
    onUpdate: () -> Unit,
    onTab: (Browse) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val appLanguageRow = remember { FocusRequester() }
    val contentLanguagesRow = remember { FocusRequester() }
    val regionRow = remember { FocusRequester() }
    var returnTo by rememberSaveable { mutableStateOf<Picker?>(null) }

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        BrowseTabs(Browse.Settings, onTab, Modifier.padding(horizontal = Spacing.screenX))
        when (picking) {
            Picker.ContentLanguages -> {
                BackHandler { picking = null }
                ContentLanguagesList(library, settings.contentLanguages, onChange = { languages -> onChange { it.copy(contentLanguages = languages) } })
            }
            Picker.Region -> {
                BackHandler { picking = null }
                RegionList(settings.region, settings.appLanguage, onPick = { region ->
                    onChange { it.copy(region = region) }
                    picking = null
                })
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
                null -> appLanguageRow
            }.requestFocus()
        }
    }
}

private enum class Picker { ContentLanguages, Region }

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

/** Every country, by its name in the [appLanguage], the Viewer's [region] first. */
@Composable
private fun RegionList(region: String, appLanguage: String, onPick: (String) -> Unit) {
    val countries = remember(appLanguage) {
        val collator = Collator.getInstance(Locale.forLanguageTag(appLanguage))
        Locale.getISOCountries().sortedWith { a, b -> collator.compare(countryName(a, appLanguage), countryName(b, appLanguage)) }
    }
    val order = remember(countries) { listOf(region) + (countries - region) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(COLUMN_WIDTH),
    ) {
        item { Heading(stringResource(R.string.settings_region)) }
        items(order, key = { it }) { country ->
            SettingRow(
                countryName(country, appLanguage),
                if (country == region) CHOSEN else "",
                onClick = { onPick(country) },
                if (country == region) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

private fun countryName(code: String, appLanguage: String): String = Locale("", code).getDisplayCountry(Locale.forLanguageTag(appLanguage))

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

/** The value of a chosen row in a list. */
private const val CHOSEN = "✓"
