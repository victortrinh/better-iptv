package dev.betteriptv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.Profiles

/**
 * What the tabs need to draw the profile chip (#432): the household's [profiles], what OK on it
 * does ([onOpen]) and the [focus] that puts focus back on it when "Who's watching?" closes.
 */
class ProfileChipState(val profiles: Profiles, val onOpen: () -> Unit, val focus: FocusRequester) {
    /** Drawn only once the household has two Profiles or more: with one nothing changes anywhere. */
    val shown: Boolean get() = profiles.all.size > 1
}

/** The profile chip the browsing screens' tabs draw, provided by [ProfileSwitcher]; none outside it. */
val LocalProfileChip = compositionLocalOf<ProfileChipState?> { null }

/**
 * The household's Profiles over [content], the browsing screens: their tabs draw the profile chip
 * (through [LocalProfileChip]), whose OK opens "Who's watching?" over the screen. Picking a Profile
 * there switches to it ([onSwitch]) and closes; "Add a profile" closes and opens Settings' add flow
 * ([onAdd]); Back closes. Focus goes back to the chip, unless a Profile was added.
 */
@Composable
fun BoxScope.ProfileSwitcher(profiles: Profiles, onSwitch: (Long) -> Unit, onAdd: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Set when it closes: focus goes back to the chip once the screen under it is focusable again.
    var backToChip by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val chip = remember(profiles) { ProfileChipState(profiles, onOpen = { open = true }, focus) }
    val close = {
        open = false
        backToChip = true
    }
    CompositionLocalProvider(LocalProfileChip provides chip) { content() }
    // A Profile deleted down to one closes it: there is nothing to pick between.
    if (open && chip.shown) {
        WhoIsWatching(
            profiles,
            onPick = { id ->
                if (id != profiles.currentId) onSwitch(id)
                close()
            },
            onAdd = {
                open = false
                onAdd()
            },
            onClose = close,
        )
    }
    if (backToChip) {
        LaunchedEffect(Unit) {
            withFrameNanos {}
            // The chip is gone when the switch left one Profile; focus then stays where the screen put it.
            runCatching { focus.requestFocus() }
            backToChip = false
        }
    }
}

/**
 * The current Profile's name in a `glass` capsule as tall as the tab capsule, at the tabs' right
 * end: a tab in the focus grammar's sense (Right from the last tab reaches it, it takes the tabs'
 * focus style). OK opens "Who's watching?".
 */
@Composable
fun ProfileChip(chip: ProfileChipState, modifier: Modifier = Modifier) {
    val capsule = RoundedCornerShape(50)
    FocusSurface(
        onClick = chip.onOpen,
        modifier = modifier
            .height(CHIP_HEIGHT)
            .widthIn(max = CHIP_MAX_WIDTH)
            .focusRequester(chip.focus)
            .border(1.dp, Colors.ink.copy(alpha = 0.14f), capsule)
            .testTag(PROFILE_CHIP_TAG)
            .semantics { role = Role.Button },
        weight = FocusWeight.Control,
        corner = CHIP_HEIGHT / 2,
        container = Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Text(
            chip.profiles.current.shownName(),
            style = Type.label,
            // Glass over artwork, as the inactive tabs: white at 85% keeps 4.5:1.
            color = if (focused) Colors.onFocus else Colors.ink.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}

/**
 * What sits at the tabs' right end: the Catalog pill ([pill], when the screen has one) and, with
 * two Profiles or more, the profile chip ([chip]) after it, so the pill shows left of the chip.
 */
@Composable
fun BoxScope.TabsEnd(chip: ProfileChipState?, pill: (@Composable () -> Unit)?) {
    val withChip = chip?.takeIf { it.shown }
    if (pill == null && withChip == null) return
    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        pill?.invoke()
        if (withChip != null) ProfileChip(withChip)
    }
}

/**
 * "Who's watching?" over the screen, on one `veil`: a headline, a row per Profile in the order they
 * were made (the current one marked ✓, a Kids one with a small "Kids" label), then "Add a profile",
 * off once the household has [Library.MAX_PROFILES]. Focus opens on the current Profile and stays
 * inside; Back closes ([onClose]).
 */
@Composable
fun WhoIsWatching(profiles: Profiles, onPick: (Long) -> Unit, onAdd: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { current.requestFocus() }
    Box(
        Modifier.fillMaxSize()
            .background(Colors.veil)
            // Keeps focus in the list: nothing under the veil can be reached while it is open.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        // The list on a `ground` slab: glass rows straight on the veil let the screen's own rows show through them.
        Column(
            Modifier.background(Colors.ground, RoundedCornerShape(Corners.card)).padding(Spacing.xl).width(SWITCHER_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.who_is_watching), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            profiles.all.forEach { profile ->
                SwitcherRow(
                    profile,
                    chosen = profile.id == profiles.currentId,
                    onClick = { onPick(profile.id) },
                    modifier = (if (profile.id == profiles.currentId) Modifier.focusRequester(current) else Modifier).testTag("switcher:${profile.id}"),
                )
            }
            val full = profiles.all.size >= Library.MAX_PROFILES
            FocusSurface(
                onClick = { if (!full) onAdd() },
                modifier = Modifier.fillMaxWidth().testTag("switcher:add"),
                weight = FocusWeight.Row,
                container = Colors.surface,
                focusedContainer = Colors.focus,
            ) { focused ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.profile_add), style = Type.label, color = if (focused) Colors.onFocus else if (full) Colors.inkMuted else Colors.ink)
                    if (full) {
                        Text(stringResource(R.string.profile_add_full, Library.MAX_PROFILES), style = Type.caption, color = if (focused) Colors.onFocus.copy(alpha = 0.7f) else Colors.inkMuted)
                    }
                }
            }
        }
    }
}

/** A Profile's row in "Who's watching?": its name, a "Kids" label on a Kids profile, ✓ on the current one. */
@Composable
private fun SwitcherRow(profile: Profile, chosen: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(onClick, modifier.fillMaxWidth(), weight = FocusWeight.Row, container = Colors.surface, focusedContainer = Colors.focus) { focused ->
        val ink = if (focused) Colors.onFocus else Colors.ink
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(profile.shownName(), style = Type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (profile.kids) {
                    Spacer(Modifier.width(Spacing.s))
                    // A label, not a button: `badge` corners, outlined in the row's ink.
                    Box(Modifier.border(1.dp, ink.copy(alpha = 0.6f), RoundedCornerShape(Corners.badge)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(stringResource(R.string.profile_kids_label), style = Type.caption, color = ink, maxLines = 1)
                    }
                }
            }
            if (chosen) {
                Spacer(Modifier.width(16.dp))
                Text(CHOSEN, style = Type.detail, color = if (focused) Colors.onFocus else Colors.inkMuted)
            }
        }
    }
}

internal const val PROFILE_CHIP_TAG = "profile-chip"

/** The tab capsule's height: a 32 dp tab in 4 dp of padding. */
private val CHIP_HEIGHT = 40.dp

/** A 20-letter name in label type, with room to spare. */
private val CHIP_MAX_WIDTH = 240.dp

private val SWITCHER_WIDTH = 520.dp
