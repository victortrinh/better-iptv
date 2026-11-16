package dev.betteriptv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fake must hold the real Provider's Connection limit, or tests could pass against a laxer Provider. */
class FakeProviderTest {
    private val provider = FakeProvider()

    @After fun tearDown() = provider.close()

    @Test fun `a second concurrent stream is refused`() {
        provider.openStream(1001).use { playing ->
            playing.read(ByteArray(1024))

            assertThrows(IllegalStateException::class.java) { provider.openStream(1002) }
        }
        assertEquals(1, provider.refusedStreams)
    }
}
