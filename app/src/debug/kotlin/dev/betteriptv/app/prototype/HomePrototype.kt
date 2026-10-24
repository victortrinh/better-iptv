package dev.betteriptv.app.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.Backdrop as ArtworkBackdrop
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

val TABS = listOf("Home", "Movies", "Shows", "Live", "Search", "Settings")

/** A screen's ground. */
@Composable
fun Ground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Colors.ground), content = content)
}

/** Sample artwork behind everything, as the shipped screens draw it. */
@Composable
fun Backdrop(image: Int) = ArtworkBackdrop(painterResource(image))

@Composable
fun HomePrototype(focusCard: Boolean) {
    val featured = SampleCatalog.featured
    val play = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (focusCard) firstCard else play).requestFocus() }

    Ground {
        Backdrop(featured.still)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.screenY)) {
            item {
                TopTabs(TABS, selected = 0, onSelect = {}, modifier = Modifier.padding(horizontal = Spacing.screenX, vertical = Spacing.screenY))
            }
            item {
                Column(Modifier.padding(start = Spacing.screenX, top = 96.dp, bottom = 28.dp).width(440.dp)) {
                    Text(featured.name, style = Type.display, color = Colors.ink, maxLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${featured.year} · ${featured.runtime} · ${featured.genres}", style = Type.body, color = Colors.inkMuted)
                        Spacer(Modifier.width(12.dp))
                        BadgeRow(featured.badges)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(featured.description, style = Type.body, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NuageButton("Play", {}, Modifier.focusRequester(play), icon = Icons.Filled.PlayArrow)
                        NuageButton("Details", {})
                    }
                }
            }
            SampleCatalog.rows.forEachIndexed { rowIndex, row ->
                item {
                    Text(row.heading, style = Type.headline, color = Colors.ink, modifier = Modifier.padding(start = Spacing.screenX, bottom = 12.dp))
                }
                item {
                    // Continue watching shows where you are in the film, so it uses stills; other rows use posters.
                    val wide = row.progress != null
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(Cards.gap),
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        itemsIndexed(row.titles) { index, title ->
                            TitleCard(
                                painterResource(if (wide) title.still else title.poster),
                                title.name,
                                title.badges,
                                onClick = {},
                                wide = wide,
                                progress = row.progress?.getOrNull(index),
                                modifier = if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstCard) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
