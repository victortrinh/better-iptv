package dev.betteriptv.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Serves the fake Provider as a real local server, for the app on an emulator: a player needs an
 * address it can open, so this one fake stays on a socket (#580). It serves one Movie, "EN - Test
 * Movie (2024)", whose file is the one named, and writes its address to `<file>.serving`; it stops
 * when that file is deleted. On the emulator the Provider is at `http://10.0.2.2:<port>`, with the
 * fake's login.
 * Skipped unless run with `./gradlew :core:jvmTest --tests '*ServedProviderTest' -PserveProvider=/path/to/movie.mp4`;
 * the movie is held in the test JVM's small heap, so keep it to a few megabytes.
 */
class ServedProviderTest : SocketTest() {
    @Test fun `the fake Provider serves an emulator until its flag file is deleted`() {
        val movie = System.getProperty("serveProvider")
        assumeTrue("run with -PserveProvider=/path/to/movie.mp4", movie != null)
        FakeProvider(OverSockets).use { provider ->
            provider.serveMovies("EN - Test Movie (2024)" to "EN - MOVIES")
            provider.movieFiles[1] = File(movie).readBytes()
            val flag = File("$movie.serving").apply { writeText(provider.url) }
            println("The fake Provider serves $movie at ${provider.url} (login ${provider.username}) until ${flag.path} is deleted.")
            while (flag.exists()) Thread.sleep(500)
            println("Asked for: ${provider.requests}; ranges: ${provider.ranges}")
        }
    }
}
