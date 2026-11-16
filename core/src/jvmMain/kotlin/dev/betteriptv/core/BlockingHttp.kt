package dev.betteriptv.core

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.runBlocking
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.InterruptedIOException

/**
 * Makes one request and has [read] its answer on the calling thread, as the Provider's and the Cloud's
 * clients are written: their JSON reader pulls bytes as it goes (Moshi, until #581), so nothing else may
 * hold the thread meanwhile. The answer's body is read as it arrives, never whole, and what [read]
 * leaves unread is dropped with the request.
 */
internal fun <T> HttpClient.blocking(request: HttpRequestBuilder.() -> Unit, read: suspend (HttpResponse) -> T): T = try {
    runBlocking { prepareRequest(request).execute { response -> read(response) } }
} catch (e: InterruptedException) {
    // As OkHttp says it: the thread stays interrupted, and the caller hears of an I/O failure.
    Thread.currentThread().interrupt()
    throw InterruptedIOException("interrupted")
}

/** The answer's body for a reader that blocks: read from the network as the reader asks for more. */
internal suspend fun HttpResponse.source(): BufferedSource = bodyAsChannel().toInputStream().source().buffer()
