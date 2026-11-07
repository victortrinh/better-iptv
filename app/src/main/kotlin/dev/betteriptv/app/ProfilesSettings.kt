package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles

/** What Settings' Profiles asks of the household's Profiles (#428). */
sealed interface ProfileChange {
    /** A new Profile named [name], a Kids profile when [kids] (#433). */
    data class Add(val name: String, val kids: Boolean = false) : ProfileChange
    data class Rename(val id: Long, val name: String) : ProfileChange
    data class Switch(val id: Long) : ProfileChange
    data class Delete(val id: Long) : ProfileChange
}

/** A Profile's name as shown: the first Profile, until renamed, is named after the device's language ("English", "Français"). */
fun Profile.shownName(): String = name ?: languageName(deviceAppLanguage())

/**
 * The Profiles section, first in Settings: a row per Profile in the order they were made, the one
 * the device is on marked ✓, each opening its page ([onOpen]); then "Add a profile" ([onAdd]),
 * disabled once the household has [Library.MAX_PROFILES], and left out when it [canAdd] none (on a
 * Kids profile). [rows] focuses each Profile's row by id, [addRow] the last row.
 */
fun LazyListScope.profilesSection(profiles: Profiles, rows: (Long) -> FocusRequester, addRow: FocusRequester, onOpen: (Profile) -> Unit, onAdd: () -> Unit, canAdd: Boolean = true) {
    item { SettingsHeading(R.string.settings_profiles) }
    items(profiles.all, key = { "profile:${it.id}" }) { profile ->
        SettingRow(
            profile.shownName(),
            if (profile.id == profiles.currentId) CHOSEN else "",
            onClick = { onOpen(profile) },
            Modifier.focusRequester(rows(profile.id)).testTag("profile:${profile.id}"),
        )
    }
    if (canAdd) item(key = "profile:add") {
        val full = profiles.all.size >= Library.MAX_PROFILES
        SettingRow(
            stringResource(R.string.profile_add),
            "",
            onClick = onAdd,
            Modifier.focusRequester(addRow),
            detail = if (full) stringResource(R.string.profile_add_full, Library.MAX_PROFILES) else null,
            enabled = !full,
        )
    }
}

/**
 * A Profile's page, in place of the Settings column: "Watch as" switches the device to it (not
 * offered on the one the device is on), "Rename" opens its name, and "Delete profile" asks first,
 * disabled with its reason for the household's last Profile. The first row takes focus.
 */
@Composable
fun ProfilePage(profile: Profile, current: Boolean, last: Boolean, onSwitch: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    SettingsColumn {
        item { SettingsHeading(profile.shownName()) }
        if (!current) {
            item {
                SettingRow(
                    stringResource(R.string.profile_switch, profile.shownName()),
                    "",
                    onClick = onSwitch,
                    Modifier.focusRequester(first),
                    detail = stringResource(R.string.profile_switch_detail),
                )
            }
        }
        item { SettingRow(stringResource(R.string.profile_rename), "", onClick = onRename, if (current) Modifier.focusRequester(first) else Modifier) }
        item {
            SettingRow(
                stringResource(R.string.profile_delete),
                "",
                onClick = onDelete,
                detail = if (last) stringResource(R.string.profile_delete_last) else null,
                enabled = !last,
            )
        }
    }
}

/**
 * Asks before deleting [profile]: what goes with it, then Cancel, which takes focus, and Delete.
 */
@Composable
fun DeleteProfilePage(profile: Profile, onDelete: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocus() }
    SettingsColumn {
        item { SettingsHeading(stringResource(R.string.profile_delete_confirm, profile.shownName())) }
        item { Text(stringResource(R.string.profile_delete_detail), style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        item { SettingRow(stringResource(R.string.profile_cancel), "", onClick = onCancel, Modifier.focusRequester(cancel)) }
        item { SettingRow(stringResource(R.string.profile_delete_yes), "", onClick = onDelete) }
    }
}

/**
 * A Profile's name typed on the [RemoteKeyboard], under [title]: the name so far in a glass field,
 * each word starting with a capital, at most [MAX_NAME] characters; Save ([onSave]) once it has a
 * letter. The keyboard's first letter takes focus. Adding a Profile ([kidsSwitch]) also offers the
 * Kids switch under them, Off unless chosen: a Kids profile sees only the Kids catalog (#433).
 */
@Composable
fun ProfileNamePage(title: String, initial: String, onSave: (String, Boolean) -> Unit, kidsSwitch: Boolean = false) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var kids by rememberSaveable { mutableStateOf(false) }
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) { entry.requestFocus() }
    Column(Modifier.padding(horizontal = Spacing.screenX, vertical = 20.dp).width(SETTINGS_WIDTH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHeading(title)
        Column {
            Text(stringResource(R.string.profile_name), style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Box(
                Modifier.width(NAME_WIDTH).height(48.dp).background(Colors.surface, RoundedCornerShape(Corners.card)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) {
                    Text(stringResource(R.string.profile_name), style = Type.body, color = Colors.inkMuted)
                } else {
                    Text(name, style = Type.body, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            RemoteKeyboard(
                onKey = { key -> if (name.length < MAX_NAME) name += if (name.isEmpty() || name.last() == ' ') key.uppercase() else key },
                onDelete = { name = name.dropLast(1) },
                onSpace = { if (name.isNotEmpty() && name.last() != ' ' && name.length < MAX_NAME) name += " " },
                entry = entry,
            )
            Spacer(Modifier.width(24.dp))
            NuageButton(stringResource(R.string.profile_save), onClick = { onSave(name.trim(), kids) }, enabled = name.isNotBlank())
        }
        if (kidsSwitch) {
            // Off unless chosen: a Kids profile sees only the Kids catalog.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(320.dp)) {
                    Text(stringResource(R.string.profile_kids), style = Type.label, color = Colors.ink)
                    Text(stringResource(R.string.profile_kids_detail), style = Type.caption, color = Colors.inkMuted)
                }
                Spacer(Modifier.width(16.dp))
                ChoiceRow(
                    listOf(stringResource(R.string.settings_off), stringResource(R.string.settings_on)),
                    selected = if (kids) 1 else 0,
                    onSelect = { kids = it == 1 },
                    modifier = Modifier.testTag("kids switch"),
                )
            }
        }
    }
}

/** A list in place of the Settings column, as wide and as far in. */
@Composable
internal fun SettingsColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(SETTINGS_WIDTH),
        content = content,
    )
}

@Composable
internal fun SettingsHeading(text: Int) = SettingsHeading(stringResource(text))

@Composable
internal fun SettingsHeading(text: String) {
    Text(text, style = Type.headline, color = Colors.ink, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** The longest Profile name: it fits the profile chip (#432) and a row with room to spare. */
private const val MAX_NAME = 20

private val NAME_WIDTH = 360.dp
