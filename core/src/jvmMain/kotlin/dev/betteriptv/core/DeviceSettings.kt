package dev.betteriptv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings the device keeps for everyone who watches on it, whatever the Profile: what the TV,
 * the device and the room are like. They never leave the device ([KeptDeviceSettings]).
 */
data class DeviceSettings(
    val qualityCap: QualityCap = QualityCap.UHD,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val backgroundReading: BackgroundReading = BackgroundReading.Gentle,
    /** How big the interface text is, for the screen and how far the Viewer sits from it. */
    val textSize: TextSize = TextSize.Medium,
)

/** How fast Background reading goes; [pace] is null when it is off. */
enum class BackgroundReading(val pace: BackgroundPace?) { Off(null), Gentle(BackgroundPace.Gentle), Normal(BackgroundPace.Normal) }

/** How big the interface text is. What each size measures is the platform's. */
enum class TextSize { Small, Medium, Large }

/**
 * How the player draws subtitles that carry no style of their own (styled ASS subtitles keep
 * theirs): their [size], [colour], what's behind them ([background]) and how high they sit ([position]).
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    val colour: SubtitleColour = SubtitleColour.White,
    val background: SubtitleBackground = SubtitleBackground.Outline,
    val position: SubtitlePosition = SubtitlePosition.Bottom,
)

/** A subtitle size. How big each is drawn is the platform's player's. */
enum class SubtitleSize { Small, Medium, Large, Largest }

enum class SubtitleColour { White, Yellow }

/** What sits behind subtitles: a dark outline round the letters, a shadow, or a dark box. */
enum class SubtitleBackground { Outline, Shadow, Box }

/**
 * How high subtitles sit, in [percent] of the screen's height from its bottom, or of the picture's
 * where the player draws them inside it, a [STEP] at a time from [LOWEST] to [HIGHEST]. [Bottom]
 * lands in the black bar under a wide picture (#292). [Raised] clears the player overlay's scrubber,
 * whose track sits 23% to 24% up a 1080-line screen (#277), on a 16:9 film too, where the screen is
 * the picture: its text ends about 2% above the track. Higher than half the screen, a line sits over faces.
 */
@JvmInline
value class SubtitlePosition(val percent: Int) {
    init {
        require(percent in LOWEST..HIGHEST && (percent - LOWEST) % STEP == 0) { "No subtitle position at $percent%" }
    }

    val fromBottom: Float get() = percent / 100f

    /** A step lower, or this one at the lowest. */
    fun lower(): SubtitlePosition = SubtitlePosition((percent - STEP).coerceAtLeast(LOWEST))

    /** A step higher, or this one at the highest. */
    fun higher(): SubtitlePosition = SubtitlePosition((percent + STEP).coerceAtMost(HIGHEST))

    companion object {
        const val LOWEST = 2
        const val HIGHEST = 50
        const val STEP = 3
        val Bottom = SubtitlePosition(5)
        val Raised = SubtitlePosition(26)

        /** The position [percent] up, or the nearest step to it within the range. */
        fun nearest(percent: Int): SubtitlePosition =
            SubtitlePosition(LOWEST + ((percent.coerceIn(LOWEST, HIGHEST) - LOWEST + STEP / 2) / STEP) * STEP)
    }
}

/**
 * Keeps the [DeviceSettings] in the platform's [SettingsStore], each under a key of its own, so every
 * platform has them without writing them again. A setting never changed, or kept by a release that
 * offered a choice this one doesn't, is its default.
 */
class KeptDeviceSettings(private val store: SettingsStore) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    /** Keeps what [change] makes of the settings. */
    fun update(change: (DeviceSettings) -> DeviceSettings) = synchronized(this) {
        val new = change(_settings.value)
        store.write(
            mapOf(
                QUALITY_CAP to new.qualityCap.name,
                SUBTITLE_SIZE to new.subtitleStyle.size.name,
                SUBTITLE_COLOUR to new.subtitleStyle.colour.name,
                SUBTITLE_BACKGROUND to new.subtitleStyle.background.name,
                SUBTITLE_HEIGHT to new.subtitleStyle.position.percent.toString(),
                BACKGROUND_READING to new.backgroundReading.name,
                TEXT_SIZE to new.textSize.name,
            ),
        )
        _settings.value = new
    }

    private fun read() = DeviceSettings(
        qualityCap = read(QUALITY_CAP, QualityCap.entries) ?: QualityCap.UHD,
        subtitleStyle = SubtitleStyle(
            size = read(SUBTITLE_SIZE, SubtitleSize.entries) ?: SubtitleSize.Medium,
            colour = read(SUBTITLE_COLOUR, SubtitleColour.entries) ?: SubtitleColour.White,
            background = read(SUBTITLE_BACKGROUND, SubtitleBackground.entries) ?: SubtitleBackground.Outline,
            position = store.read(SUBTITLE_HEIGHT)?.toIntOrNull()?.let(SubtitlePosition::nearest) ?: SubtitlePosition.Bottom,
        ),
        backgroundReading = read(BACKGROUND_READING, BackgroundReading.entries) ?: BackgroundReading.Gentle,
        textSize = read(TEXT_SIZE, TextSize.entries) ?: TextSize.Medium,
    )

    private fun <T : Enum<T>> read(key: String, entries: List<T>): T? = store.read(key)?.let { name -> entries.find { it.name == name } }

    /** The keys the settings are kept under: the names the Android app kept them by before the Core did. */
    companion object {
        const val QUALITY_CAP = "quality_cap"
        const val SUBTITLE_SIZE = "subtitle_size"
        const val SUBTITLE_COLOUR = "subtitle_colour"
        const val SUBTITLE_BACKGROUND = "subtitle_background"
        const val SUBTITLE_HEIGHT = "subtitle_height"
        const val BACKGROUND_READING = "background_reading"
        const val TEXT_SIZE = "text_size"
    }
}
