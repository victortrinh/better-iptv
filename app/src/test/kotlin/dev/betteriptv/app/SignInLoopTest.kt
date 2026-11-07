package dev.betteriptv.app

import dev.betteriptv.core.SignInCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** The Sign-in screen's loop (#429): a code, a poll every 2 s while it shows, a new code when it expires. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInLoopTest {
    private val shown = mutableListOf<SignInState>()
    private var codes = 0
    private var checks = 0
    private var signedInAs: String? = null
    private var cloudUp = true

    private suspend fun start(): SignInCode? {
        if (!cloudUp) return null
        codes++
        return SignInCode("ABC23$codes", "https://zugig.app/sign-in?code=ABC23$codes", Duration.ofMinutes(10))
    }

    private suspend fun check(): String? {
        checks++
        return signedInAs
    }

    @Test fun `shows a code and asks every 2 seconds whether the phone signed the TV in`() = runTest {
        val run = async { runSignIn(::start, ::check, shown::add) }
        runCurrent()
        assertEquals(SignInState.Showing(SignInCode("ABC231", "https://zugig.app/sign-in?code=ABC231", Duration.ofMinutes(10))), shown.single())
        assertEquals(0, checks)
        advanceTimeBy(2_001)
        assertEquals(1, checks)
        advanceTimeBy(8_000)
        assertEquals(5, checks)

        signedInAs = "home@example.com"
        advanceTimeBy(2_000)
        assertEquals("home@example.com", run.await())
        assertEquals(SignInState.SignedIn("home@example.com"), shown.last())
        assertEquals(6, checks)
    }

    @Test fun `asks a new code before this one expires`() = runTest {
        val run = async { runSignIn(::start, ::check, shown::add) }
        advanceTimeBy(Duration.ofMinutes(10).minusSeconds(30).toMillis())
        assertEquals(1, codes)
        advanceTimeBy(Duration.ofSeconds(30).toMillis())
        assertEquals(2, codes)
        assertEquals("ABC232", (shown.last() as SignInState.Showing).code.code)
        assertFalse(run.isCompleted)
        run.cancel()
    }

    @Test fun `says so while the cloud can't give a code, and tries again`() = runTest {
        cloudUp = false
        val run = async { runSignIn(::start, ::check, shown::add) }
        runCurrent()
        assertEquals(SignInState.Unavailable, shown.single())
        assertEquals(0, checks)
        cloudUp = true
        advanceTimeBy(5_001)
        assertTrue(shown.last() is SignInState.Showing)
        run.cancel()
    }
}
