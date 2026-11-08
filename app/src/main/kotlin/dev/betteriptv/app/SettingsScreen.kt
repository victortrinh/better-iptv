package dev.betteriptv.app

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.betteriptv.core.HouseholdPin
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profiles
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.UpdateState
import dev.betteriptv.core.offered
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * The Viewer's settings, in a single column: App language, Content languages, Region and Leagues under
 * Languages, the Text size under Display, the Quality cap and whether the next Episode plays by itself under Playback,
 * Sign in (or who the device is signed in as) under Account, Sync now
 * ([onSyncNow]) with how the Sync is going and the Title counts under Catalog, the App version under
 * App, which offers the [update] when there is one ([onUpdate]). Content languages and Region
 * each open a list, Leagues its logo grid ([LeaguePicker]) and Sign in its code and QR ([SignInScreen]), in place of the
 * column; Back returns to the row that opened it, as signing in does.
 * Above them, the household's [profiles] ([profilesSection]): a Profile's row opens its page, to watch as it, rename it or
 * delete it, and "Add a profile" its name; each asks [onProfile]. The Languages and "Play next Episode" are the current
 * Profile's, the rest the device's.
 * [focusTab]: reached by picking its tab, which keeps focus; otherwise the current Profile's row takes it.
 * [addProfile] opens "Add a profile"'s name at once, as "Who's watching?" asks (#432), and says so through [onAddProfileShown];
 * Back from it lands on "Add a profile".
 * On a Kids profile there are only the Profiles, whose rows switch to another Profile once the Household [pin] is
 * typed (#437), and the Text size. Elsewhere the first Kids profile added chooses the PIN, and "Change PIN" changes it.
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
    profiles: Profiles,
    onProfile: (ProfileChange) -> Unit,
    addProfile: Boolean = false,
    onAddProfileShown: () -> Unit = {},
) = SettingsContent(
    settings, update, onChange, onUpdate, focusTab, onTab, profiles, onProfile,
    pin = library.householdPin,
    addProfile = addProfile,
    onAddProfileShown = onAddProfileShown,
    catalogLanguages = library::catalogLanguages,
    catalog = {
        item { SyncRow(library, onSyncNow) }
    },
    account = { open, accountRow, devicesRow -> AccountRows(library, open, accountRow, devicesRow) },
    accountPage = { page, onDone -> AccountPageContent(library, page, onDone) },
)

/**
 * [SettingsScreen] without the library: the catalog's languages come from [catalogLanguages], and
 * the Catalog section's rows from [catalog], the Account section's rows from [account] and what they
 * open (the Sign-in screen, Sign out, the device list) from [accountPage].
 */
@Composable
internal fun SettingsContent(
    settings: ViewerSettings,
    update: UpdateState,
    onChange: ((ViewerSettings) -> ViewerSettings) -> Unit,
    onUpdate: () -> Unit,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    profiles: Profiles,
    onProfile: (ProfileChange) -> Unit,
    pin: HouseholdPin,
    catalogLanguages: suspend () -> List<String>,
    catalog: LazyListScope.() -> Unit,
    account: @Composable (open: (AccountPage) -> Unit, accountRow: Modifier, devicesRow: Modifier) -> Unit = { _, _, _ -> },
    accountPage: @Composable (page: AccountPage, onDone: () -> Unit) -> Unit = { _, _ -> },
    addProfile: Boolean = false,
    onAddProfileShown: () -> Unit = {},
) {
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    // The Profile whose page, name or deletion is open, or whose row Back returns to; null while adding one.
    var profileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val profileRows = remember { HashMap<Long, FocusRequester>() }
    val profileRow = { id: Long -> profileRows.getOrPut(id) { FocusRequester() } }
    val addProfileRow = remember { FocusRequester() }
    val changePinRow = remember { FocusRequester() }
    val picked = profiles.all.find { it.id == profileId }
    val pinSet by pin.isSet.collectAsStateWithLifecycle()
    // The name of the first Kids profile, kept while its Household PIN is chosen (#437): it is added once there is one.
    var kidsName by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // A Kids profile's Settings offer the Text size and the Profile switch, nothing else.
    val kids = profiles.current.kids
    val appLanguageRow = remember { FocusRequester() }
    val contentLanguagesRow = remember { FocusRequester() }
    val regionRow = remember { FocusRequester() }
    val leaguesRow = remember { FocusRequester() }
    val accountRow = remember { FocusRequester() }
    val devicesRow = remember { FocusRequester() }
    var returnTo by rememberSaveable { mutableStateOf<Picker?>(null) }
    // Kept while a list or the Sign-in screen replaces the column, so Back lands with the row that
    // opened it composed: the Account row is below the first screenful.
    val settingsList = rememberLazyListState()
    val tab = remember { FocusRequester() }
    FocusTabOnArrival(focusTab, tab)
    if (addProfile) {
        LaunchedEffect(Unit) {
            // A Kids profile adds none (#433).
            if (!kids) {
                profileId = null
                returnTo = Picker.ProfileName
                picking = Picker.ProfileName
            }
            onAddProfileShown()
        }
    }

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        BrowseTabs(Browse.Settings, onTab, Modifier.padding(horizontal = Spacing.screenX), selectedFocus = tab)
        // A list opening, or Back to the settings, fades in under the tabs, which stay put.
        Box(Modifier.weight(1f).fillMaxWidth()) { when (picking) {
            Picker.ContentLanguages -> {
                BackHandler { picking = null }
                ContentLanguagesList(catalogLanguages, settings.contentLanguages, onChange = { languages -> onChange { it.copy(contentLanguages = languages) } })
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
                LeaguePicker(settings.leaguesOn, onChange = { leagues -> onChange { it.copy(leagues = leagues) } })
            }
            Picker.Profile -> if (picked == null) {
                // Deleted meanwhile: back to the list.
                LaunchedEffect(Unit) { picking = null }
            } else {
                BackHandler { picking = null }
                ProfilePage(
                    picked,
                    current = picked.id == profiles.currentId,
                    last = profiles.all.size == 1,
                    onSwitch = {
                        onProfile(ProfileChange.Switch(picked.id))
                        picking = null
                    },
                    onRename = { picking = Picker.ProfileName },
                    onDelete = { picking = Picker.DeleteProfile },
                )
            }
            Picker.ProfileName -> {
                // Back from adding returns to "Add a profile", from renaming to the Profile's page.
                val back = {
                    kidsName = null
                    picking = if (picked == null) null else Picker.Profile
                }
                BackHandler(onBack = back)
                ProfileNamePage(
                    if (picked == null) stringResource(R.string.profile_add) else stringResource(R.string.profile_rename_title, picked.shownName()),
                    initial = kidsName ?: picked?.name.orEmpty(),
                    onSave = { name, kids ->
                        if (picked == null && kids && !pinSet) {
                            // The household's first Kids profile: the PIN that lets someone leave it comes first.
                            kidsName = name
                            picking = Picker.ChoosePin
                        } else {
                            onProfile(if (picked == null) ProfileChange.Add(name, kids) else ProfileChange.Rename(picked.id, name))
                            back()
                        }
                    },
                    // The Kids switch is chosen once, when the Profile is made.
                    kidsSwitch = picked == null,
                    initialKids = kidsName != null,
                )
            }
            Picker.ChoosePin -> {
                // Back returns to the name, as typed, the Kids switch on: no Profile is made without its PIN.
                BackHandler { picking = Picker.ProfileName }
                PinPage {
                    ChoosePin(stringResource(R.string.pin_choose), stringResource(R.string.pin_choose_detail), onChosen = { chosen ->
                        val name = kidsName
                        scope.launch {
                            pin.set(chosen)
                            if (name != null) onProfile(ProfileChange.Add(name, kids = true))
                            kidsName = null
                            picking = null
                        }
                    })
                }
            }
            Picker.SwitchPin -> if (picked == null) {
                LaunchedEffect(Unit) { picking = null }
            } else {
                BackHandler { picking = null }
                PinPage {
                    LeaveKidsPin(pin, stringResource(R.string.pin_title), stringResource(R.string.pin_to_watch_as, picked.shownName()), onRight = {
                        onProfile(ProfileChange.Switch(picked.id))
                        picking = null
                    })
                }
            }
            Picker.ChangePin -> {
                BackHandler { picking = null }
                // Reset from the phone: there is no current PIN to ask.
                if (!pinSet) LaunchedEffect(Unit) { picking = Picker.NewPin }
                else PinPage { AskPin(pin, stringResource(R.string.pin_current), detail = null, onRight = { picking = Picker.NewPin }) }
            }
            Picker.NewPin -> {
                BackHandler { picking = null }
                PinPage {
                    ChoosePin(stringResource(R.string.pin_new), detail = null, onChosen = { chosen ->
                        scope.launch {
                            pin.set(chosen)
                            picking = null
                        }
                    })
                }
            }
            Picker.DeleteProfile -> if (picked == null) {
                LaunchedEffect(Unit) { picking = null }
            } else {
                BackHandler { picking = Picker.Profile }
                DeleteProfilePage(
                    picked,
                    onDelete = {
                        onProfile(ProfileChange.Delete(picked.id))
                        profileId = null
                        picking = null
                    },
                    onCancel = { picking = Picker.Profile },
                )
            }
            Picker.SignIn, Picker.SignOut, Picker.Devices -> {
                BackHandler { picking = null }
                accountPage(
                    when (picking) {
                        Picker.SignOut -> AccountPage.SignOut
                        Picker.Devices -> AccountPage.Devices
                        else -> AccountPage.SignIn
                    },
                ) { picking = null }
            }
            null -> LazyColumn(
                state = settingsList,
                contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(SETTINGS_WIDTH),
            ) {
                profilesSection(
                    profiles,
                    rows = profileRow,
                    addRow = addProfileRow,
                    onOpen = { profile ->
                        if (kids) {
                            // A Kids profile switches, and nothing more, once the Household PIN is typed.
                            if (profile.id != profiles.currentId) {
                                profileId = profile.id
                                returnTo = Picker.SwitchPin
                                picking = Picker.SwitchPin
                            }
                        } else {
                            profileId = profile.id
                            returnTo = Picker.Profile
                            picking = Picker.Profile
                        }
                    },
                    onAdd = {
                        profileId = null
                        returnTo = Picker.ProfileName
                        picking = Picker.ProfileName
                    },
                    canAdd = !kids,
                )
                // Outside a Kids profile, once the household has a PIN.
                if (!kids && pinSet) {
                    item(key = "profile:pin") {
                        SettingRow(
                            stringResource(R.string.pin_change),
                            "",
                            onClick = {
                                returnTo = Picker.ChangePin
                                picking = Picker.ChangePin
                            },
                            Modifier.focusRequester(changePinRow),
                            detail = stringResource(R.string.pin_change_detail),
                        )
                    }
                }
                if (!kids) {
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
                    Labelled(stringResource(R.string.settings_prefer_original_audio), stringResource(R.string.settings_prefer_original_audio_detail)) {
                        ChoiceRow(
                            listOf(stringResource(R.string.settings_on), stringResource(R.string.settings_off)),
                            selected = if (settings.preferOriginalAudio) 0 else 1,
                            onSelect = { index -> onChange { it.copy(preferOriginalAudio = index == 0) } },
                        )
                    }
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
                    SettingRow(
                        stringResource(R.string.settings_leagues),
                        leaguesValue(settings.leaguesOn),
                        onClick = {
                            returnTo = Picker.Leagues
                            picking = Picker.Leagues
                        },
                        Modifier.focusRequester(leaguesRow),
                        detail = stringResource(R.string.settings_leagues_detail),
                    )
                }
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
                if (!kids) {
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
                item { Heading(stringResource(R.string.settings_account)) }
                item {
                    account({ page ->
                        val opened = when (page) {
                            AccountPage.SignIn -> Picker.SignIn
                            AccountPage.SignOut -> Picker.SignOut
                            AccountPage.Devices -> Picker.Devices
                        }
                        returnTo = opened
                        picking = opened
                    }, Modifier.focusRequester(accountRow), Modifier.focusRequester(devicesRow))
                }
                item { Heading(stringResource(R.string.settings_catalog)) }
                catalog()
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
                        // Where what the app knows beyond the Provider's catalog comes from (#480): Title facts and Trending.
                        detail = stringResource(R.string.settings_data_sources),
                    )
                }
                }
            }
        }; ScreenFade(picking, firstFades = false) }
    }
    if (picking == null) {
        LaunchedEffect(Unit) {
            // After the App language recreates the screen, the rows aren't placed on the first frame yet.
            withFrameNanos {}
            when (returnTo) {
                Picker.ContentLanguages -> contentLanguagesRow
                Picker.Region -> regionRow
                Picker.Leagues -> leaguesRow
                // Back to the Profile's row; once it's deleted, to the current one's.
                Picker.Profile, Picker.DeleteProfile, Picker.SwitchPin -> profileRow(picked?.id ?: profiles.currentId)
                Picker.ProfileName, Picker.ChoosePin -> addProfileRow
                Picker.ChangePin, Picker.NewPin -> changePinRow
                Picker.SignIn, Picker.SignOut -> accountRow
                Picker.Devices -> devicesRow
                // Reached by its tab, which keeps focus.
                null -> profileRow(profiles.currentId).takeUnless { focusTab }
            }?.requestFocus()
        }
    }
}

/**
 * Sync now, with how the Sync is going as its value (in the error colour when it failed, its "ago"
 * ticking each minute), a later Sync's fraction as its bar, and the Title counts under it, with the
 * Mismatches the year rule kept apart. Off only while a Sync runs.
 */
@Composable
private fun SyncRow(library: Library, onSyncNow: () -> Unit) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    val counts by produceState<Pair<Int, Int>?>(null, catalog) {
        value = withContext(Dispatchers.IO) { library.titleCount(TitleKind.Movie) to library.titleCount(TitleKind.Show) }
    }
    val minute by produceState(0, status) {
        while (true) {
            delay(AGO_REFRESH.toMillis())
            value++
        }
    }
    val context = LocalContext.current
    val value = remember(status, minute) { context.describe(status) }
    SettingRow(
        stringResource(R.string.sync_now),
        value,
        onClick = onSyncNow,
        detail = counts?.let { (movies, shows) -> context.catalogCounts(movies, shows, status.last?.mismatches ?: 0) },
        progress = (status as? SyncStatus.Running)?.fraction,
        enabled = status !is SyncStatus.Running,
        valueColor = if (status is SyncStatus.Failed) Colors.error else Colors.inkMuted,
        valueLines = 2,
    )
}

/** How often the Sync row's "ago" is worded again while Settings is open. */
private val AGO_REFRESH: Duration = Duration.ofMinutes(1)

private enum class Picker {
    ContentLanguages, Region, Leagues,

    /** A Profile's page, or Back to its row. */
    Profile,

    /** A Profile's name, to add one (no Profile picked) or rename one. */
    ProfileName,

    /** The question before deleting a Profile. */
    DeleteProfile,

    /** The Sign-in code and QR. */
    SignIn,

    /** Choosing the Household PIN, for the household's first Kids profile. */
    ChoosePin,

    /** The Household PIN, before a Kids profile switches to the picked Profile, or Back to its row. */
    SwitchPin,

    /** "Change PIN": the current one first, then [NewPin]. */
    ChangePin,
    NewPin,

    /** The question before signing this TV out. */
    SignOut,

    /** The Account's devices. */
    Devices,
}

/**
 * The catalog's languages to turn on and off as Content languages, the chosen ones first in the
 * order they were chosen: the first one the Title has is the one Play picks. The last one can't be
 * turned off.
 */
@Composable
private fun ContentLanguagesList(catalogLanguages: suspend () -> List<String>, chosen: List<String>, onChange: (List<String>) -> Unit) {
    val catalog by produceState(emptyList<String>()) { value = catalogLanguages() }
    // Kept from opening, so a language turned off doesn't jump away from under focus.
    val order = remember(catalog) { (chosen + catalog).distinct() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(SETTINGS_WIDTH),
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
            modifier = Modifier.width(SETTINGS_WIDTH),
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
private fun Heading(text: String) = SettingsHeading(text)

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

internal val SETTINGS_WIDTH = 760.dp

private val LETTER = 36.dp

/** The value of a chosen row in a list. */
internal const val CHOSEN = "✓"
