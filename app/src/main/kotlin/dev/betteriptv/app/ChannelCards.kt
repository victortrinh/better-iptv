package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.ChannelCard
import dev.betteriptv.core.Channel

/** [channel]'s card: pressing it watches it ([onWatch]), the menu key adds or removes the Favourite ([onFavourite], with the new state). */
@Composable
fun ChannelCard(
    channel: Channel,
    onWatch: (Channel) -> Unit,
    onFavourite: (Channel, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    named: Boolean = true,
    onFocus: () -> Unit = {},
) {
    ChannelCard(
        logo = rememberArtwork(channel.image, card = true),
        name = channel.name,
        menuHint = channel.menuHint(),
        onClick = { onWatch(channel) },
        onMenu = { onFavourite(channel, !channel.favourite) },
        modifier = modifier,
        named = named,
        onFocus = onFocus,
    )
}

/** What the menu key does on this Channel's card: add or remove the Favourite. */
@Composable
fun Channel.menuHint(): String = stringResource(if (favourite) R.string.channel_remove_favourite else R.string.channel_add_favourite)
