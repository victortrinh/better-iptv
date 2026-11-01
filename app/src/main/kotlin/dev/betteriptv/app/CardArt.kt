package dev.betteriptv.app

import dev.betteriptv.core.TitleAbout

/**
 * What a poster card shows: its [poster] at rest, with the [posterLogo] laid over it when the poster
 * is a textless one, and what it grows into once focused ([grown]); null when it stays a poster.
 */
data class CardArt(val poster: String?, val posterLogo: String?, val grown: GrownArt?)

/** The horizontal art a focused poster card grows into (#216): a backdrop with no text on it, named at its bottom left. */
sealed interface GrownArt {
    val backdrop: String

    /** Named by the Title's logo. */
    data class WithLogo(override val backdrop: String, val logo: String) : GrownArt

    /** Named in title type: TMDB has no logo for the Title. */
    data class WithName(override val backdrop: String) : GrownArt
}

/**
 * The art of a poster card for a Title whose regular poster is [image], from what TMDB tells
 * [about] it. It grows only into a textless backdrop, named by its logo, else by its name; without
 * one it stays the regular poster, which carries its name, and doesn't grow. With [textlessPosters]
 * (Home, Movies and Shows), a Title with a logo and a textless poster rests on that poster with the
 * logo over it, so its name never shows twice.
 */
fun cardArt(image: String?, about: TitleAbout?, textlessPosters: Boolean): CardArt {
    val logo = about?.logo
    val grown = about?.textlessBackdrop?.let { backdrop -> if (logo != null) GrownArt.WithLogo(backdrop, logo) else GrownArt.WithName(backdrop) }
    val textless = about?.textlessPoster?.takeIf { textlessPosters && logo != null }
    return CardArt(textless ?: image, logo.takeIf { textless != null }, grown)
}
