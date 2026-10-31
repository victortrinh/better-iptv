package dev.betteriptv.app

import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.RememberedTracks
import dev.betteriptv.core.Track
import dev.betteriptv.core.TrackChoice
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** VLC lists a file's Tracks one by one as it opens it: the choice follows until the Viewer picks one. */
class ListedTracksTest {
    private val device = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val version = Version(1, "FR - Coyote vs. Acme (2026)", VersionAttributes())
    private val plan = PlayPlan(TitleId.Tmdb(TitleKind.Movie, 1), version, listOf("fr"), listOf(version))
    private val frenchSubtitles = RememberedTracks(audio = "fr", subtitles = "fr")
    private val listed = ListedTracks { audio, subtitles -> plan.chooseTracks(audio, subtitles, device, frenchSubtitles) }

    private val audio = listOf(Track("fre", "audio/ac3"), Track("eng", "audio/ac3"))

    @Test
    fun theFileOwnSubtitlesListedAfterTheSoundWinOverDownloadedOnes() {
        assertEquals(TrackChoice(0, null, downloaded = "fr"), listed.listed(audio, emptyList()))

        assertEquals(TrackChoice(0, 0), listed.listed(audio, listOf(Track("fre"))))
    }

    @Test
    fun nothingNewListedLeavesTheChoiceAsItIs() {
        listed.listed(audio, listOf(Track("fre")))

        assertNull(listed.listed(audio, listOf(Track("fre"))))
    }

    @Test
    fun noChoiceBeforeVlcKnowsTheSound() {
        assertNull(listed.listed(emptyList(), listOf(Track("fre"))))
    }

    @Test
    fun aPickByTheViewerStandsWhateverVlcListsAfter() {
        listed.listed(audio, emptyList())
        listed.picked()

        assertNull(listed.listed(audio, listOf(Track("fre"))))
    }
}
