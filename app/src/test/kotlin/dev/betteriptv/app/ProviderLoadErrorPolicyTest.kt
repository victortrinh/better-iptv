package dev.betteriptv.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ProviderLoadErrorPolicyTest {
    private val policy = ProviderLoadErrorPolicy()
    private val media3 = DefaultLoadErrorHandlingPolicy()
    private val dataSpec = DataSpec(Uri.parse("http://provider.example/movie/viewer/secret/1.mkv"))

    private fun answered(code: Int) = InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))

    private fun info(exception: IOException, errorCount: Int = 1) =
        LoadErrorInfo(LoadEventInfo(LoadEventInfo.getNewId(), dataSpec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), exception, errorCount)

    @Test
    fun aDeadListingIsNotAskedForAgain() {
        for (code in listOf(400, 401, 404, 410, 451)) {
            assertEquals("HTTP $code", C.TIME_UNSET, policy.getRetryDelayMsFor(info(answered(code))))
        }
    }

    @Test
    fun aRefusedLoginIsNotAskedForAgain() {
        // The Provider answers a wrong login with 513 and a missing one with 511.
        for (code in listOf(511, 513)) {
            assertEquals("HTTP $code", C.TIME_UNSET, policy.getRetryDelayMsFor(info(answered(code))))
        }
    }

    @Test
    fun aDeadListingWrappedInAnotherErrorIsNotAskedForAgain() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException(answered(404)))))
    }

    @Test
    fun aProviderInPassingTroubleIsStillAskedForAgain() {
        for (code in listOf(500, 502, 503, 504)) {
            for (errorCount in 1..3) {
                val error = info(answered(code), errorCount)
                assertEquals("HTTP $code, try $errorCount", media3.getRetryDelayMsFor(error), policy.getRetryDelayMsFor(error))
                assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(error))
            }
        }
    }

    @Test
    fun aStreamRefusedForNowIsStillAskedForAgain() {
        // 403 and 429 may be the Connection limit, while the Provider still counts the stream Media3 closed to seek.
        for (code in listOf(403, 408, 429)) {
            for (errorCount in 1..3) {
                val error = info(answered(code), errorCount)
                assertEquals("HTTP $code, try $errorCount", media3.getRetryDelayMsFor(error), policy.getRetryDelayMsFor(error))
                assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(error))
            }
        }
    }

    @Test
    fun aTimeoutIsStillAskedForAgain() {
        val timeout = HttpDataSourceException(SocketTimeoutException(), dataSpec, HttpDataSourceException.TYPE_READ)
        for (errorCount in 1..3) {
            val error = info(timeout, errorCount)
            assertEquals(media3.getRetryDelayMsFor(error), policy.getRetryDelayMsFor(error))
            assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(error))
        }
    }

    @Test
    fun retriesAsOftenAsMedia3() {
        assertEquals(media3.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA), policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
