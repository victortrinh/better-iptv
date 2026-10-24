package dev.betteriptv.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.betteriptv.app.R

/**
 * Colour roles. Every screen reads these, never raw colours.
 * [accent] is reserved for what the Viewer can act on: the focused control and Play.
 */
@Immutable
data class NuageColors(
    val ground: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val onAccent: Color,
    /** Fill of a Confirmed badge. */
    val confirmed: Color,
    val onConfirmed: Color,
    /** Outline and text of a Likely badge. */
    val likely: Color,
    /** Progress, warnings that are not errors (CAM, Audio description). */
    val caution: Color,
    val error: Color,
)

@Immutable
data class NuageType(
    /** The featured Title's name in the hero and on the details page. */
    val display: TextStyle,
    /** Row headings and screen headings. */
    val headline: TextStyle,
    /** A Title's name on a card or a Version's main line. */
    val title: TextStyle,
    val body: TextStyle,
    /** Buttons, tabs, settings rows. */
    val label: TextStyle,
    /** Badges and metadata. */
    val caption: TextStyle,
)

/** How a focused card or control shows focus from the couch. */
enum class FocusKind {
    /** An accent frame drawn a few dp outside the card. */
    OffsetFrame,
    /** An accent bar under the card and a thin ink ring. */
    Underline,
    /** A paper mat around the poster, like a framed print, with the wall label shown under it. */
    Mat,
    /** The card lifts on a real shadow with an accent ring. */
    Lift,
    /** An ink ring and a larger scale, the platform convention. */
    Ring,
    /** tvOS: the card grows and lifts on a deep shadow, with a light sheen across it. No outline. */
    Parallax,
}

enum class TabStyle {
    /** Text tabs next to the wordmark. */
    Inline,
    /** Text tabs centred on the screen. */
    Centered,
    /** Centred tabs in a translucent capsule, like tvOS. */
    GlassPill,
}

/** Which home rows use landscape cards (a still) instead of posters. */
enum class WideCards { None, ContinueWatching, All }

/** What a Title card shows under its poster. */
enum class CardCaption {
    /** Name and badges, always. */
    Always,
    /** Badges always; the name only on focus. */
    BadgesAlways,
    /** Name and badges only on focus. */
    OnFocus,
}

enum class LikelyMark {
    /** A solid outline. */
    Outline,
    /** A dashed outline. */
    Dashed,
}

@Immutable
data class NuageShape(val card: Dp, val control: Dp, val badge: Dp)

/** A complete visual world for the app. The Viewer picks one in #3; the others are deleted. */
@Immutable
data class Direction(
    val id: String,
    val name: String,
    val colors: NuageColors,
    val type: NuageType,
    val shape: NuageShape,
    val focus: FocusKind,
    val focusedScale: Float,
    val likelyMark: LikelyMark,
    val cardCaption: CardCaption,
    /** Width of a poster card on the home rows. */
    val posterWidth: Dp,
    /** Gap between cards in a row. */
    val cardGap: Dp,
    /** Whether the ground takes a tint from the focused Title (Nuage's signature). */
    val groundFollowsFocus: Boolean = false,
    /** Whether the hero's backdrop fills the whole screen behind the rows. */
    val heroFullBleed: Boolean = false,
    val tabs: TabStyle = TabStyle.Inline,
    /** Controls are translucent over the backdrop, and Play looks like every other button until focused. */
    val glass: Boolean = false,
    val wideCards: WideCards = WideCards.None,
    /** Width of a landscape card. */
    val wideWidth: Dp = 196.dp,
)

/** Space, shared by every direction. Sized for a 960×540 dp TV canvas. */
object Spacing {
    /** Horizontal overscan-safe margin. */
    val screenX = 48.dp
    /** Vertical overscan-safe margin. */
    val screenY = 27.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private fun family(vararg fonts: Font) = FontFamily(*fonts)

private val archivo = family(
    Font(R.font.archivo_400, FontWeight.Normal),
    Font(R.font.archivo_600, FontWeight.SemiBold),
    Font(R.font.archivo_800, FontWeight.ExtraBold),
)
private val archivoNarrow = family(
    Font(R.font.archivo_narrow_500, FontWeight.Medium),
    Font(R.font.archivo_narrow_700, FontWeight.Bold),
)
private val overpass = family(
    Font(R.font.overpass_400, FontWeight.Normal),
    Font(R.font.overpass_600, FontWeight.SemiBold),
    Font(R.font.overpass_800, FontWeight.ExtraBold),
)
private val sourceSerif = family(Font(R.font.source_serif_600, FontWeight.SemiBold))
private val publicSans = family(
    Font(R.font.public_sans_400, FontWeight.Normal),
    Font(R.font.public_sans_600, FontWeight.SemiBold),
)
private val inter = family(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
    Font(R.font.inter_800, FontWeight.ExtraBold),
)
private val albertSans = family(
    Font(R.font.albert_sans_300, FontWeight.Light),
    Font(R.font.albert_sans_500, FontWeight.Medium),
    Font(R.font.albert_sans_700, FontWeight.Bold),
)

private fun typeScale(
    displayFamily: FontFamily,
    displayWeight: FontWeight,
    textFamily: FontFamily,
    displaySize: Int = 44,
    displayTracking: Double = -0.02,
    headlineFamily: FontFamily = displayFamily,
    headlineWeight: FontWeight = displayWeight,
    strongWeight: FontWeight = FontWeight.SemiBold,
) = NuageType(
    display = TextStyle(fontFamily = displayFamily, fontWeight = displayWeight, fontSize = displaySize.sp, lineHeight = (displaySize * 1.02).sp, letterSpacing = displayTracking.em),
    headline = TextStyle(fontFamily = headlineFamily, fontWeight = headlineWeight, fontSize = 20.sp, lineHeight = 26.sp),
    title = TextStyle(fontFamily = textFamily, fontWeight = strongWeight, fontSize = 16.sp, lineHeight = 22.sp),
    body = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = textFamily, fontWeight = strongWeight, fontSize = 15.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = textFamily, fontWeight = strongWeight, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em),
)

object Directions {
    /** A Montréal repertory cinema's printed programme: condensed headings, tabular columns, one yellow. */
    val Repertoire = Direction(
        id = "repertoire",
        name = "Répertoire",
        colors = NuageColors(
            ground = Color(0xFF0F1218),
            surface = Color(0xFF181D26),
            surfaceHigh = Color(0xFF242B37),
            ink = Color(0xFFF2EFE9),
            inkMuted = Color(0xFFA9ADB5),
            accent = Color(0xFFF4C542),
            onAccent = Color(0xFF14110A),
            confirmed = Color(0xFFF2EFE9),
            onConfirmed = Color(0xFF0F1218),
            likely = Color(0xFFC9CCD2),
            caution = Color(0xFFE8955A),
            error = Color(0xFFFF8A80),
        ),
        type = typeScale(archivoNarrow, FontWeight.Bold, archivo, displaySize = 52, displayTracking = -0.01),
        shape = NuageShape(card = 2.dp, control = 2.dp, badge = 2.dp),
        focus = FocusKind.OffsetFrame,
        focusedScale = 1.04f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.Always,
        posterWidth = 112.dp,
        cardGap = 14.dp,
    )

    /** Montréal on a winter night: snow-blue ground, road-sign type, sodium-lamp amber for focus. */
    val Lampadaire = Direction(
        id = "lampadaire",
        name = "Lampadaire",
        colors = NuageColors(
            ground = Color(0xFF0A0F16),
            surface = Color(0xFF141C27),
            surfaceHigh = Color(0xFF1E2938),
            ink = Color(0xFFE9EEF3),
            inkMuted = Color(0xFF97A5B4),
            accent = Color(0xFFFFA62B),
            onAccent = Color(0xFF1A1004),
            confirmed = Color(0xFFE9EEF3),
            onConfirmed = Color(0xFF0A0F16),
            likely = Color(0xFFB9C4D0),
            caution = Color(0xFFFF7B5C),
            error = Color(0xFFFF8A80),
        ),
        type = typeScale(overpass, FontWeight.ExtraBold, overpass, displaySize = 46, displayTracking = -0.025),
        shape = NuageShape(card = 6.dp, control = 6.dp, badge = 4.dp),
        focus = FocusKind.Underline,
        focusedScale = 1.06f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.BadgesAlways,
        posterWidth = 104.dp,
        cardGap = 12.dp,
    )

    /** A museum's evening galleries: charcoal walls, framed posters, wall labels set in a serif. */
    val Galerie = Direction(
        id = "galerie",
        name = "Galerie",
        colors = NuageColors(
            ground = Color(0xFF1A1817),
            surface = Color(0xFF24211F),
            surfaceHigh = Color(0xFF302C29),
            ink = Color(0xFFEFE9E0),
            inkMuted = Color(0xFFB2AA9F),
            accent = Color(0xFFEFE9E0),
            onAccent = Color(0xFF1A1817),
            confirmed = Color(0xFFEFE9E0),
            onConfirmed = Color(0xFF1A1817),
            likely = Color(0xFFC7BFB3),
            caution = Color(0xFFE0A46B),
            error = Color(0xFFFF8A80),
        ),
        type = typeScale(sourceSerif, FontWeight.SemiBold, publicSans, displaySize = 48, displayTracking = -0.015, strongWeight = FontWeight.SemiBold),
        shape = NuageShape(card = 0.dp, control = 0.dp, badge = 0.dp),
        focus = FocusKind.Mat,
        focusedScale = 1.03f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.OnFocus,
        posterWidth = 124.dp,
        cardGap = 22.dp,
    )

    /** The app's name: a cloud over the river at dusk. Light display type; the ground takes the focused Title's colour. */
    val Nuage = Direction(
        id = "nuage",
        name = "Nuage",
        colors = NuageColors(
            ground = Color(0xFF10161D),
            surface = Color(0xFF1A232D),
            surfaceHigh = Color(0xFF26323F),
            ink = Color(0xFFEEF3F7),
            inkMuted = Color(0xFF9DABB8),
            accent = Color(0xFFA8D4F5),
            onAccent = Color(0xFF0B1620),
            confirmed = Color(0xFFA8D4F5),
            onConfirmed = Color(0xFF0B1620),
            likely = Color(0xFFA8D4F5),
            caution = Color(0xFFF2B880),
            error = Color(0xFFFF8A80),
        ),
        type = typeScale(albertSans, FontWeight.Light, albertSans, displaySize = 54, displayTracking = -0.03, headlineWeight = FontWeight.Medium, strongWeight = FontWeight.Medium),
        shape = NuageShape(card = 12.dp, control = 24.dp, badge = 12.dp),
        focus = FocusKind.Lift,
        focusedScale = 1.08f,
        likelyMark = LikelyMark.Dashed,
        cardCaption = CardCaption.OnFocus,
        posterWidth = 108.dp,
        cardGap = 16.dp,
        groundFollowsFocus = true,
    )

    /** The category standard played straight: graphite, Roboto, a white ring and a big scale. */
    val Canon = Direction(
        id = "canon",
        name = "Canon",
        colors = NuageColors(
            ground = Color(0xFF111214),
            surface = Color(0xFF1C1D20),
            surfaceHigh = Color(0xFF2A2B2F),
            ink = Color(0xFFF1F1F2),
            inkMuted = Color(0xFFA4A6AB),
            accent = Color(0xFFF1F1F2),
            onAccent = Color(0xFF111214),
            confirmed = Color(0xFFF1F1F2),
            onConfirmed = Color(0xFF111214),
            likely = Color(0xFFBDBFC4),
            caution = Color(0xFFF0B86E),
            error = Color(0xFFFF8A80),
        ),
        type = typeScale(FontFamily.Default, FontWeight.Medium, FontFamily.Default, displaySize = 40, displayTracking = 0.0, strongWeight = FontWeight.Medium),
        shape = NuageShape(card = 8.dp, control = 20.dp, badge = 4.dp),
        focus = FocusKind.Ring,
        focusedScale = 1.1f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.OnFocus,
        posterWidth = 100.dp,
        cardGap = 14.dp,
    )

    /** tvOS with Liquid Glass: full-bleed artwork, translucent controls, cards that lift with a sheen. */
    val Verre = Direction(
        id = "verre",
        name = "Verre",
        colors = NuageColors(
            ground = Color(0xFF07080A),
            surface = Color(0x1FFFFFFF),
            surfaceHigh = Color(0x33FFFFFF),
            ink = Color(0xFFFFFFFF),
            inkMuted = Color(0xFFA9A9AF),
            accent = Color(0xFFFFFFFF),
            onAccent = Color(0xFF000000),
            confirmed = Color(0xFFE8E8ED),
            onConfirmed = Color(0xFF000000),
            likely = Color(0xFFC7C7CC),
            caution = Color(0xFFFFB35C),
            error = Color(0xFFFF6B60),
        ),
        type = typeScale(inter, FontWeight.Bold, inter, displaySize = 50, displayTracking = -0.03),
        shape = NuageShape(card = 12.dp, control = 22.dp, badge = 4.dp),
        focus = FocusKind.Parallax,
        focusedScale = 1.12f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.OnFocus,
        posterWidth = 104.dp,
        cardGap = 20.dp,
        heroFullBleed = true,
        tabs = TabStyle.GlassPill,
        glass = true,
        wideCards = WideCards.ContinueWatching,
    )

    /** Apple TV+: an opaque, cinematic black; big landscape stills in every row; white Play. */
    val GrandEcran = Direction(
        id = "grand-ecran",
        name = "Grand écran",
        colors = NuageColors(
            ground = Color(0xFF000000),
            surface = Color(0xFF1C1C1E),
            surfaceHigh = Color(0xFF2C2C2E),
            ink = Color(0xFFFFFFFF),
            inkMuted = Color(0xFF98989F),
            accent = Color(0xFFFFFFFF),
            onAccent = Color(0xFF000000),
            confirmed = Color(0xFFE8E8ED),
            onConfirmed = Color(0xFF000000),
            likely = Color(0xFFAEAEB2),
            caution = Color(0xFFFFB35C),
            error = Color(0xFFFF6B60),
        ),
        type = typeScale(inter, FontWeight.ExtraBold, inter, displaySize = 56, displayTracking = -0.035, headlineWeight = FontWeight.Bold),
        shape = NuageShape(card = 10.dp, control = 10.dp, badge = 3.dp),
        focus = FocusKind.Parallax,
        focusedScale = 1.08f,
        likelyMark = LikelyMark.Outline,
        cardCaption = CardCaption.Always,
        posterWidth = 104.dp,
        cardGap = 18.dp,
        heroFullBleed = true,
        tabs = TabStyle.Centered,
        wideCards = WideCards.All,
        wideWidth = 204.dp,
    )

    val all = listOf(Repertoire, Lampadaire, Galerie, Nuage, Canon, Verre, GrandEcran)

    fun byId(id: String?): Direction = all.firstOrNull { it.id == id } ?: Repertoire
}
