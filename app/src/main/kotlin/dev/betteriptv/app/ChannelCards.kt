package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.ChannelCard
import dev.betteriptv.core.Channel
import dev.betteriptv.core.LiveCountry

/**
 * [channel]'s card: pressing it watches it ([onWatch]), the menu key adds or removes the Favourite
 * ([onFavourite], with the new state). Its caption is the Channel's country tag and what the menu
 * key does ("UK · ≡ Add to Favourites").
 */
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
        caption = channel.caption(),
        onClick = { onWatch(channel) },
        onMenu = { onFavourite(channel, !channel.favourite) },
        modifier = modifier,
        named = named,
        onFocus = onFocus,
    )
}

/** What a country card and the Channels screen call this country: its alias when the Provider's code isn't a country, else its name in the [appLanguage]. */
fun LiveCountry.name(appLanguage: String): String = alias ?: countryName(code, appLanguage)

/** What the menu key does on this Channel's card: add or remove the Favourite. */
@Composable
fun Channel.menuHint(): String = stringResource(if (favourite) R.string.channel_remove_favourite else R.string.channel_add_favourite)

/**
 * The caption under this Channel's name: its country tag, when it has one, then what the menu key
 * does ("UK · ≡ Add to Favourites"). Under a focused Favourite, what the Channel is showing
 * ([programme], [nowNextLabel]) takes the country tag's place when the guide knows it: "The National
 * · Next 10:00 PM · ≡ Remove from Favourites".
 */
@Composable
fun Channel.caption(programme: String? = null): String = listOfNotNull(programme ?: country, menuHint()).joinToString(" · ")
