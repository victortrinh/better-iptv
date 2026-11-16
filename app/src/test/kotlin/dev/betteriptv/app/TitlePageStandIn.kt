package dev.betteriptv.app

import androidx.compose.runtime.Composable
import dev.betteriptv.core.Episode
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.ShowEpisodes
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.Version
import dev.betteriptv.core.titlePageContent

/**
 * A [TitlePage] stood in from what the Core reads a page from, as the screen tests have it: the
 * details and their plan ([loaded]), and on a Show its Episodes and their plan ([show]), each null
 * until read. The Core makes the page's content of them ([titlePageContent]), as the Library does
 * for a real page, so a test says what was read and checks what the screen shows.
 */
@Composable
internal fun TitlePage(
    id: TitleId,
    loaded: Pair<TitleDetails, PlayPlan?>?,
    show: Pair<ShowEpisodes, PlayPlan?>?,
    onPlay: (PlayPlan, String) -> Unit,
    section: TitleSection? = null,
    returnedFrom: TitleSection? = null,
    onSection: (TitleSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
    onRemove: () -> Unit = {},
    onChoose: (Version, String) -> Unit = { _, _ -> },
    onEpisode: (Episode, String) -> Unit = { _, _ -> },
    contentLanguages: List<String> = emptyList(),
    kids: Boolean = false,
    checking: Boolean = false,
    checked: Boolean = true,
) = TitlePage(
    id,
    loaded?.let { (details, plan) -> titlePageContent(details, plan ?: show?.second, show?.first, contentLanguages, kids, checking, checked) },
    onPlay, section, returnedFrom, onSection, onCloseSection, onRemove, onChoose, onEpisode,
)
