package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.betteriptv.core.Library
import dev.betteriptv.core.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A screen's content from the Core ([Library.screen]), held for a screen that reads what it shows
 * ([RowsLayout]'s `read` and `reading`): [changes] moves each time the Core hands over content that
 * differs from what the screen read last, and [read] gives it. The screen works nothing out; it reads
 * again when [changes] moves, and draws.
 */
@Stable
internal class LiveContent<Content : Any>(private val flow: Flow<Content>) {
    /** How many times the content has changed since the screen first read it: part of what the screen reads for. */
    var changes by mutableIntStateOf(0)
        private set

    /** The content handed over last, and whether the screen has yet to read it. */
    private var latest: Content? = null
    private var unread = false
    private val arrived = CompletableDeferred<Unit>()

    /** Follows the Core's content while the screen shows. */
    suspend fun collect() = flow.collect { content ->
        if (content == latest) return@collect
        val first = latest == null
        latest = content
        unread = true
        // The first is what an entry waits for ([read]); each one after tells the screen to read again.
        if (first) arrived.complete(Unit) else changes++
    }

    /**
     * What is on the screen: the content the Core handed over, when the screen hasn't read it yet;
     * else the Core is asked now, as when the screen reads for another reason (a Favourite it just
     * saved, a Sync it saw finish). An entry waits for the first content.
     */
    suspend fun read(): Content {
        arrived.await()
        if (unread) {
            unread = false
            return latest!!
        }
        return flow.first().also { latest = it }
    }
}

/**
 * The Core's content for [screen], followed while the app is on screen ([LiveContent]): behind
 * another app or a sleeping TV nothing is read, and the content is asked for again on coming back.
 */
@Composable
internal fun <Content : Any> liveContent(library: Library, screen: Screen<Content>): LiveContent<Content> {
    val content = remember(library, screen) { LiveContent(library.screen(screen)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(content, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { content.collect() } }
    return content
}
