package dev.betteriptv.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** The type scale at each Text size (#223). */
class TypeScaleTest {
    private fun TypeScale.styles(): Map<String, TextStyle> = mapOf(
        "display" to display, "headline" to headline, "title" to title, "body" to body,
        "railMeta" to railMeta, "rail" to rail, "railStrong" to railStrong,
        "detail" to detail, "label" to label, "caption" to caption,
    )

    private fun TypeScale.sizes(): Map<String, Pair<Float, Float>> =
        styles().mapValues { (_, style) -> style.fontSize.value to style.lineHeight.value }

    @Test
    fun largeIsTheScaleWithRailInfoAtBodySize() {
        assertEquals(
            mapOf(
                "display" to (42f to 44f), "headline" to (15f to 20f), "title" to (14f to 19f), "body" to (14f to 20f),
                "railMeta" to (12f to 17f), "rail" to (14f to 20f), "railStrong" to (14f to 20f),
                "detail" to (13f to 18f), "label" to (13f to 18f), "caption" to (11f to 15f),
            ),
            TypeScale.of(TextSize.Large).sizes(),
        )
    }

    @Test
    fun mediumIsLargeTimesNineTenthsInWholeSp() {
        assertEquals(
            mapOf(
                "display" to (38f to 40f), "headline" to (14f to 18f), "title" to (13f to 17f), "body" to (13f to 18f),
                "railMeta" to (11f to 15f), "rail" to (13f to 18f), "railStrong" to (13f to 18f),
                "detail" to (12f to 16f), "label" to (12f to 16f), "caption" to (10f to 14f),
            ),
            TypeScale.of(TextSize.Medium).sizes(),
        )
    }

    @Test
    fun smallIsMediumTimesNineTenths() {
        val medium = TypeScale.of(TextSize.Medium).sizes()
        TypeScale.of(TextSize.Small).sizes().forEach { (name, size) ->
            val (fontSize, lineHeight) = medium.getValue(name)
            assertEquals(name, fontSize * 0.9f, size.first, 0.001f)
            assertEquals(name, lineHeight * 0.9f, size.second, 0.001f)
        }
    }

    @Test
    fun scalingKeepsWeightsAndTracking() {
        val large = TypeScale.of(TextSize.Large).styles()
        for (size in TextSize.entries) {
            TypeScale.of(size).styles().forEach { (name, style) ->
                assertEquals(name, large.getValue(name).fontWeight, style.fontWeight)
                assertEquals(name, large.getValue(name).letterSpacing, style.letterSpacing)
            }
        }
        assertEquals(FontWeight.Bold, TypeScale.of(TextSize.Medium).railStrong.fontWeight)
        assertEquals(FontWeight.SemiBold, TypeScale.of(TextSize.Medium).rail.fontWeight)
    }

    @Test
    fun theTopRowNumeralDoesNotScale() {
        assertEquals(200f, Type.numeral.fontSize.value)
        assertEquals(200f, Type.numeral.lineHeight.value)
    }
}
