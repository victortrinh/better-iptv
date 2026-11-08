package dev.betteriptv.app

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A card's artwork still waits 250 ms before it loads, unless it is already in memory; other artwork never waits. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettleFirstTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val url = "https://img.example/poster.jpg"

    private class Chain(override val request: ImageRequest) : Interceptor.Chain {
        var loaded = false
        override val size: Size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest) = Chain(request)
        override fun withSize(size: Size) = this
        override suspend fun proceed(): ImageResult {
            loaded = true
            return SuccessResult(ColorDrawable().asImage(), request)
        }
    }

    private fun request(card: Boolean) = ImageRequest.Builder(context).data(url).apply { if (card) extras[SettleFirst.Key] = true }.build()

    @Test fun aCardsArtworkLoadsOnlyOnceItHasBeenWanted250Ms() = runTest {
        val chain = Chain(request(card = true))
        launch { SettleFirst(inMemory = { false }).intercept(chain) }

        advanceTimeBy(249)
        assertFalse(chain.loaded)
        advanceTimeBy(2)
        assertTrue(chain.loaded)
    }

    @Test fun aCardsArtworkAlreadyInMemoryAndAnyOtherArtworkLoadAtOnce() = runTest {
        val inMemory = Chain(request(card = true))
        val backdrop = Chain(request(card = false))
        launch { SettleFirst(inMemory = { it == url }).intercept(inMemory) }
        launch { SettleFirst(inMemory = { false }).intercept(backdrop) }

        runCurrent()
        assertTrue(inMemory.loaded)
        assertTrue(backdrop.loaded)
    }
}
