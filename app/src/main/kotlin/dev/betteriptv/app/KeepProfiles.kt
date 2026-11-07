package dev.betteriptv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Profile
import dev.betteriptv.core.ProfileChoice
import dev.betteriptv.core.ProfileToKeep

/**
 * "Keep which profiles?" over the screen (#440): signing in would bring this TV's Profiles and the
 * Account's past [ProfileChoice.most], so the TV asks which to keep before sign-in completes. A row
 * per Profile, the Account's first, saying where it is and ticked to start with; OK ticks or unticks
 * one, and the one the TV is on stays ticked. "Keep these" ([onKeep]) once at most [ProfileChoice.most]
 * are ticked: an unticked one is deleted, from every TV of the Account when it is the Account's.
 * "Don't sign in" and Back ([onCancel]) leave the TV signed out, as it was. Focus opens on the first
 * row and stays inside.
 */
@Composable
fun KeepProfiles(choice: ProfileChoice, onKeep: (Set<String>) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var kept by rememberSaveable(choice) { mutableStateOf(choice.profiles.map { it.key }.toSet()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    OnVeil {
        Text(stringResource(R.string.keep_profiles_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(start = 4.dp))
        Text(
            stringResource(R.string.keep_profiles_detail, choice.most),
            style = Type.body,
            color = Colors.inkMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        choice.profiles.forEachIndexed { i, profile ->
            val ticked = profile.key in kept
            SettingRow(
                profile.shownName(),
                if (ticked) CHOSEN else "",
                onClick = { if (!profile.current) kept = if (ticked) kept - profile.key else kept + profile.key },
                (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("keep:${profile.key}"),
                detail = listOfNotNull(
                    stringResource(
                        when {
                            profile.current -> R.string.keep_profiles_watching
                            profile.inAccount && profile.onThisTv -> R.string.keep_profiles_both
                            profile.inAccount -> R.string.keep_profiles_account
                            else -> R.string.keep_profiles_this_tv
                        },
                    ),
                    if (profile.kids) stringResource(R.string.profile_kids_label) else null,
                ).joinToString(" · "),
            )
        }
        val over = kept.size - choice.most
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start) {
            NuageButton(stringResource(R.string.keep_profiles_keep), onClick = { onKeep(kept) }, Modifier.testTag("keep:done"), enabled = over <= 0)
            Spacer(Modifier.width(16.dp))
            NuageButton(stringResource(R.string.keep_profiles_cancel), onClick = onCancel)
        }
        if (over > 0) {
            Text(
                pluralStringResource(R.plurals.keep_profiles_over, over, over),
                style = Type.caption,
                color = Colors.inkMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** A Profile to keep's name as shown, as [Profile.shownName] shows one on the TV. */
private fun ProfileToKeep.shownName(): String = Profile(0, name, kids).shownName()
