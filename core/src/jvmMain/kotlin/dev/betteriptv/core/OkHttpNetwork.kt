package dev.betteriptv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Android's [CoreNetwork], all of it on [okHttp]: the Core's client runs on it underneath and the relay
 * reads the Provider with it, so a player engine and the artwork given the same [okHttp] share one
 * connection pool with the catalog and the Header reads.
 */
fun okHttpNetwork(okHttp: OkHttpClient = providerOkHttp()): CoreNetwork =
    CoreNetwork(coreHttp(okHttp)) { location -> LocalStreamRelay(location, okHttp) }

/** The OkHttp client the Provider and the Cloud are read with, unless the app brings its own. */
fun providerOkHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/** The client app updates are read with unless the app brings one: its own, with OkHttp's timeouts, as before (#579). */
internal fun updatesHttp(): HttpClient = coreHttp(OkHttpClient())

/**
 * The Core's HTTP client over [okHttp]: its connection pool and timeouts, and the requests OkHttp made
 * for the Core before it had this client (#579), so the Provider is asked as it always was.
 */
fun coreHttp(okHttp: OkHttpClient = providerOkHttp()): HttpClient = HttpClient(OkHttp) {
    engine {
        // Ktor builds its clients from this one, which shares the connection pool.
        preconfigured = okHttp
        config {
            // Ktor gives each client it builds a dispatcher that sends five requests to a host at a time;
            // the Core's requests waited in no such line before.
            dispatcher(Dispatcher().apply { maxRequestsPerHost = maxRequests })
            // Redirects are followed by the client below, which then knows where an answer finally came from.
            followRedirects(false)
            followSslRedirects(false)
            addInterceptor(AsOkHttpAsks)
        }
    }
    // A request may bound itself (the Cloud's Games and Provider table); the rest keep [okHttp]'s timeouts.
    install(HttpTimeout)
    // As OkHttp follows them: a Provider may answer over HTTPS and send its files from an HTTP host.
    install(HttpRedirect) { allowHttpsDowngrade = true }
    // A refusal is an answer the Core reads (HTTP 513 is a wrong login), never an exception.
    expectSuccess = false
}

/**
 * Takes off the headers Ktor adds to every request, so OkHttp fills in its own: its User-Agent, and
 * `Accept-Encoding: gzip`, which it then inflates as the answer arrives. A Provider answers by who
 * asks, and it has only ever been asked by OkHttp.
 */
private object AsOkHttpAsks : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val asked = chain.request()
        val asOkHttp = asked.newBuilder().apply {
            if (asked.header("User-Agent")?.startsWith("ktor", ignoreCase = true) == true) removeHeader("User-Agent")
            if (asked.header("Accept") == "*/*") removeHeader("Accept")
            removeHeader("Accept-Charset")
        }.build()
        return chain.proceed(asOkHttp)
    }
}
