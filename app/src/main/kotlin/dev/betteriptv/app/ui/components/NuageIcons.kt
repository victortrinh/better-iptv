package dev.betteriptv.app.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The few Material icons the app needs beyond `material-icons-core`. Copied from
 * `material-icons-extended` 1.7.8 (Apache 2.0), whose several megabytes the stick shouldn't carry.
 */
object NuageIcons {
    val Pause: ImageVector by lazy {
        materialIcon(name = "Filled.Pause") {
            materialPath {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                lineTo(10.0f, 5.0f)
                lineTo(6.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                lineTo(18.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }
    }

    val Replay10: ImageVector by lazy {
        materialIcon(name = "Filled.Replay10") {
            materialPath {
                moveTo(11.99f, 5.0f)
                verticalLineTo(1.0f)
                lineToRelative(-5.0f, 5.0f)
                lineToRelative(5.0f, 5.0f)
                verticalLineTo(7.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                reflectiveCurveTo(16.41f, 5.0f, 11.99f, 5.0f)
                close()
            }
            materialPath {
                moveTo(10.89f, 16.0f)
                horizontalLineToRelative(-0.85f)
                verticalLineToRelative(-3.26f)
                lineToRelative(-1.01f, 0.31f)
                verticalLineToRelative(-0.69f)
                lineToRelative(1.77f, -0.63f)
                horizontalLineToRelative(0.09f)
                verticalLineTo(16.0f)
                close()
            }
            materialPath {
                moveTo(15.17f, 14.24f)
                curveToRelative(0.0f, 0.32f, -0.03f, 0.6f, -0.1f, 0.82f)
                reflectiveCurveToRelative(-0.17f, 0.42f, -0.29f, 0.57f)
                reflectiveCurveToRelative(-0.28f, 0.26f, -0.45f, 0.33f)
                reflectiveCurveToRelative(-0.37f, 0.1f, -0.59f, 0.1f)
                reflectiveCurveToRelative(-0.41f, -0.03f, -0.59f, -0.1f)
                reflectiveCurveToRelative(-0.33f, -0.18f, -0.46f, -0.33f)
                reflectiveCurveToRelative(-0.23f, -0.34f, -0.3f, -0.57f)
                reflectiveCurveToRelative(-0.11f, -0.5f, -0.11f, -0.82f)
                verticalLineTo(13.5f)
                curveToRelative(0.0f, -0.32f, 0.03f, -0.6f, 0.1f, -0.82f)
                reflectiveCurveToRelative(0.17f, -0.42f, 0.29f, -0.57f)
                reflectiveCurveToRelative(0.28f, -0.26f, 0.45f, -0.33f)
                reflectiveCurveToRelative(0.37f, -0.1f, 0.59f, -0.1f)
                reflectiveCurveToRelative(0.41f, 0.03f, 0.59f, 0.1f)
                curveToRelative(0.18f, 0.07f, 0.33f, 0.18f, 0.46f, 0.33f)
                reflectiveCurveToRelative(0.23f, 0.34f, 0.3f, 0.57f)
                reflectiveCurveToRelative(0.11f, 0.5f, 0.11f, 0.82f)
                verticalLineTo(14.24f)
                close()
                moveTo(14.32f, 13.38f)
                curveToRelative(0.0f, -0.19f, -0.01f, -0.35f, -0.04f, -0.48f)
                reflectiveCurveToRelative(-0.07f, -0.23f, -0.12f, -0.31f)
                reflectiveCurveToRelative(-0.11f, -0.14f, -0.19f, -0.17f)
                reflectiveCurveToRelative(-0.16f, -0.05f, -0.25f, -0.05f)
                reflectiveCurveToRelative(-0.18f, 0.02f, -0.25f, 0.05f)
                reflectiveCurveToRelative(-0.14f, 0.09f, -0.19f, 0.17f)
                reflectiveCurveToRelative(-0.09f, 0.18f, -0.12f, 0.31f)
                reflectiveCurveToRelative(-0.04f, 0.29f, -0.04f, 0.48f)
                verticalLineToRelative(0.97f)
                curveToRelative(0.0f, 0.19f, 0.01f, 0.35f, 0.04f, 0.48f)
                reflectiveCurveToRelative(0.07f, 0.24f, 0.12f, 0.32f)
                reflectiveCurveToRelative(0.11f, 0.14f, 0.19f, 0.17f)
                reflectiveCurveToRelative(0.16f, 0.05f, 0.25f, 0.05f)
                reflectiveCurveToRelative(0.18f, -0.02f, 0.25f, -0.05f)
                reflectiveCurveToRelative(0.14f, -0.09f, 0.19f, -0.17f)
                reflectiveCurveToRelative(0.09f, -0.19f, 0.11f, -0.32f)
                reflectiveCurveToRelative(0.04f, -0.29f, 0.04f, -0.48f)
                verticalLineTo(13.38f)
                close()
            }
        }
    }

    val Forward10: ImageVector by lazy {
        materialIcon(name = "Filled.Forward10") {
            materialPath {
                moveTo(18.0f, 13.0f)
                curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
                reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                verticalLineToRelative(4.0f)
                lineToRelative(5.0f, -5.0f)
                lineToRelative(-5.0f, -5.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, 3.58f, -8.0f, 8.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                horizontalLineTo(18.0f)
                close()
            }
            materialPath {
                moveTo(10.86f, 15.94f)
                lineToRelative(0.0f, -4.27f)
                lineToRelative(-0.09f, 0.0f)
                lineToRelative(-1.77f, 0.63f)
                lineToRelative(0.0f, 0.69f)
                lineToRelative(1.01f, -0.31f)
                lineToRelative(0.0f, 3.26f)
                close()
            }
            materialPath {
                moveTo(12.25f, 13.44f)
                verticalLineToRelative(0.74f)
                curveToRelative(0.0f, 1.9f, 1.31f, 1.82f, 1.44f, 1.82f)
                curveToRelative(0.14f, 0.0f, 1.44f, 0.09f, 1.44f, -1.82f)
                verticalLineToRelative(-0.74f)
                curveToRelative(0.0f, -1.9f, -1.31f, -1.82f, -1.44f, -1.82f)
                curveTo(13.55f, 11.62f, 12.25f, 11.53f, 12.25f, 13.44f)
                close()
                moveTo(14.29f, 13.32f)
                verticalLineToRelative(0.97f)
                curveToRelative(0.0f, 0.77f, -0.21f, 1.03f, -0.59f, 1.03f)
                curveToRelative(-0.38f, 0.0f, -0.6f, -0.26f, -0.6f, -1.03f)
                verticalLineToRelative(-0.97f)
                curveToRelative(0.0f, -0.75f, 0.22f, -1.01f, 0.59f, -1.01f)
                curveTo(14.07f, 12.3f, 14.29f, 12.57f, 14.29f, 13.32f)
                close()
            }
        }
    }

    val Subtitles: ImageVector by lazy {
        materialIcon(name = "Filled.Subtitles") {
            materialPath {
                moveTo(20.0f, 4.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(4.0f, 12.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 14.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(14.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(20.0f, 18.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(20.0f, 14.0f)
                lineTo(10.0f, 14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }
}
