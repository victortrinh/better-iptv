package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * Speaks the Xtream Codes `player_api`. Responses are read as they arrive (OkHttp asks for gzip and
 * inflates on the fly), so no whole JSON document is ever held in memory.
 *
 * The login travels in every URL, so no URL or raw error message leaves this class: failures become
 * [ProviderException]s with the credentials scrubbed out.
 */
internal class XtreamClient(private val login: ProviderLogin, private val http: OkHttpClient) {
    private val base: HttpUrl = baseUrl(login.serverUrl)

    /** Checks the login and the subscription; throws a [ProviderException] when either is refused. */
    fun account(): LoggedIn {
        var auth: String? = null
        var status: String? = null
        var expiresOn: Instant? = null
        api(null) { json ->
            // A wrong login gets `{"user_info":{"auth":0}}`, or from some Providers an empty list.
            if (json.peek() != JsonReader.Token.BEGIN_OBJECT) return@api json.skipValue()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "user_info" || json.peek() != JsonReader.Token.BEGIN_OBJECT) { json.skipValue(); continue }
                json.beginObject()
                while (json.hasNext()) when (json.nextName()) {
                    "auth" -> auth = json.nextScalar()
                    "status" -> status = json.nextScalar()
                    "exp_date" -> expiresOn = json.nextScalar()?.toLongOrNull()?.let(Instant::ofEpochSecond)
                    else -> json.skipValue()
                }
                json.endObject()
            }
            json.endObject()
        }
        if (auth != "1") throw ProviderException(WrongLogin)
        if (status.equals("Expired", ignoreCase = true)) throw ProviderException(Expired(expiresOn))
        return LoggedIn(expiresOn)
    }

    fun <T> api(action: String?, read: (JsonReader) -> T): T {
        val url = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", login.username)
            .addQueryParameter("password", login.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (action == null && response.code in setOf(401, 403)) throw ProviderException(WrongLogin)
                if (!response.isSuccessful) throw ProviderException(Unreachable("The Provider answered HTTP ${response.code}."))
                return JsonReader.of(response.body!!.source()).use(read)
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            val detail = scrub(e.message ?: "") .ifBlank { e.javaClass.simpleName }
            throw ProviderException(Unreachable("Couldn't read from the Provider: $detail"))
        }
    }

    private fun scrub(text: String) =
        listOf(login.password, login.username).filter { it.isNotEmpty() }.fold(text) { acc, secret -> acc.replace(secret, "***") }

    companion object {
        /** Accepts a bare host, host:port, or a pasted player link, like the survey script. */
        fun baseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim()
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return try {
                withScheme.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build()
            } catch (e: IllegalArgumentException) {
                throw ProviderException(Unreachable("That server address doesn't look right."))
            }
        }
    }
}

internal class ProviderException(val problem: ProviderProblem) : IOException(problem.toString())

/** Reads any JSON scalar as text: the Provider sends ids and dates as strings or numbers, inconsistently. */
internal fun JsonReader.nextScalar(): String? = when (peek()) {
    JsonReader.Token.NULL -> nextNull<String>()
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> nextString()
    JsonReader.Token.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

internal inline fun JsonReader.eachObject(read: JsonReader.() -> Unit) {
    if (peek() != JsonReader.Token.BEGIN_ARRAY) throw ProviderException(Unreachable("The Provider sent something other than a list."))
    beginArray()
    while (hasNext()) {
        if (peek() != JsonReader.Token.BEGIN_OBJECT) { skipValue(); continue }
        beginObject()
        read()
        endObject()
    }
    endArray()
}
