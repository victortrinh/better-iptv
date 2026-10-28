package dev.betteriptv.core

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/**
 * Speaks the app's cloud API (cloud/src/index.ts). The device signs in with its own token, which
 * it registers for on first use; no Provider login, address or stream URL is ever sent. File reads
 * are shared under a hash of the Provider's host ([providerKey]), which doesn't reveal it.
 */
internal class CloudClient(address: String, private val http: OkHttpClient, private val tokens: DeviceTokenStore) {
    private val base: HttpUrl = address.toHttpUrl()

    /**
     * TMDB metadata for [ids] in [language] (ISO 639-1), at most [MAX_BATCH] at a time. Titles TMDB
     * couldn't answer for right now are in neither part of the answer.
     */
    fun metadata(language: String, ids: List<TitleId.Tmdb>): CloudMetadata {
        require(ids.size <= MAX_BATCH)
        val body = mapOf(
            "language" to language,
            "titles" to ids.map { mapOf("kind" to kindName(it.kind), "tmdbId" to it.tmdbId) },
        )
        val answer = authorized { token -> post("v1/metadata", json.toJson(body), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return CloudMetadata(
            found = (answer["titles"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { title -> titleId(title)?.let { it to json.toJson(title) } }.toMap(),
            notFound = (answer["notFound"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull(::titleId),
        )
    }

    /**
     * The Title each of [questions] belongs to, at most [MAX_MATCH_BATCH] at a time: TMDB's ID, or
     * null when TMDB has no Title by that name. Questions TMDB couldn't answer for right now are left out.
     */
    fun match(questions: List<MatchQuestion>): Map<MatchQuestion, Long?> {
        require(questions.size <= MAX_MATCH_BATCH)
        val body = mapOf(
            "listings" to questions.map {
                mapOf("kind" to kindName(it.kind), "name" to it.name, "year" to it.year, "language" to it.language, "tmdbId" to it.tmdbId)
            },
        )
        val answer = authorized { token -> post("v1/match", json.serializeNulls().toJson(body), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        val matches = answer["matches"] as? List<*>
        if (matches?.size != questions.size) throw CloudException("The cloud didn't answer every Listing.")
        return buildMap {
            questions.zip(matches).forEach { (question, match) ->
                if (match is Map<*, *>) put(question, (match["tmdbId"] as? Number)?.toLong())
            }
        }
    }

    /**
     * What TMDB's search finds for [query] (ADR 0009): Titles by any of their names, with their
     * popularity, and people with every Title they acted in or directed.
     */
    fun search(query: String): CloudSearch {
        val answer = authorized { token -> post("v1/search", json.toJson(mapOf("query" to query)), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return CloudSearch(
            titles = (answer["titles"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { title ->
                titleId(title)?.let { it to ((title["popularity"] as? Number)?.toDouble() ?: 0.0) }
            },
            people = (answer["people"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { person ->
                val name = person["name"] as? String ?: return@mapNotNull null
                name to (person["titles"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull(::titleId)
            },
        )
    }

    /**
     * What the files of [provider]'s movie Listings [listingIds] hold, as Viewers of the same Provider
     * read them, as JSON by Listing id; at most [MAX_BATCH] at a time. [provider] is [providerKey]'s.
     */
    fun fileReads(provider: String, listingIds: List<Long>): Map<Long, String> {
        require(listingIds.size <= MAX_BATCH)
        val answer = authorized { token -> post("v1/file-reads/lookup", json.toJson(mapOf("provider" to provider, "listings" to listingIds)), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return (answer["files"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { file ->
            val id = (file["listingId"] as? Number)?.toLong() ?: return@mapNotNull null
            (file["file"] as? Map<*, *>)?.let { id to json.toJson(it) }
        }.toMap()
    }

    /** Shares what the files of [provider]'s movie Listings hold ([files], JSON by Listing id), at most [MAX_BATCH] at a time. */
    fun shareFileReads(provider: String, files: Map<Long, String>) {
        require(files.size <= MAX_BATCH)
        val body = mapOf("provider" to provider, "files" to files.map { (id, file) -> mapOf("listingId" to id, "file" to json.fromJson(file)) })
        authorized { token -> post("v1/file-reads", json.serializeNulls().toJson(body), token) }
    }

    /** Runs [call] with the device's token, registering first if it has none, and again if the cloud no longer knows it. */
    private fun authorized(call: (token: String) -> Any?): Any? {
        val token = tokens.deviceToken() ?: register()
        return try {
            call(token)
        } catch (_: Unauthorized) {
            call(register())
        }
    }

    private fun register(): String {
        val token = (post("v1/devices", "", token = null) as? Map<*, *>)?.get("token") as? String
            ?: throw CloudException("The cloud didn't give this device a token.")
        tokens.saveDeviceToken(token)
        return token
    }

    private fun post(path: String, body: String, token: String?): Any? {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .post(body.toRequestBody(JSON))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw Unauthorized()
                if (!response.isSuccessful) throw CloudException("The cloud answered HTTP ${response.code}.")
                if (response.code == 204) return null
                return JsonReader.of(response.body!!.source()).use { it.readJsonValue() }
            }
        } catch (e: IOException) {
            throw e as? CloudException ?: CloudException("Couldn't reach the cloud: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: JsonDataException) {
            throw CloudException("The cloud sent something unreadable: ${e.message}")
        }
    }

    private fun titleId(title: Map<*, *>): TitleId.Tmdb? {
        val kind = TitleKind.entries.find { kindName(it) == title["kind"] } ?: return null
        val id = (title["tmdbId"] as? Number)?.toLong() ?: return null
        return TitleId.Tmdb(kind, id)
    }

    private class Unauthorized : CloudException("The cloud didn't accept this device's token.")

    companion object {
        /** The most Titles one metadata request may ask for. */
        const val MAX_BATCH = 50

        /** The most Listings one match request may ask about. */
        const val MAX_MATCH_BATCH = 25
        private val JSON = "application/json".toMediaType()
        private val json = Moshi.Builder().build().adapter(Any::class.java)

        private fun kindName(kind: TitleKind) = when (kind) {
            TitleKind.Movie -> "movie"
            TitleKind.Show -> "show"
        }
    }
}

/** What the cloud answered: each found Title's metadata as JSON, and the Titles TMDB doesn't have. */
internal class CloudMetadata(val found: Map<TitleId.Tmdb, String>, val notFound: List<TitleId.Tmdb>)

/** What TMDB's search found: Titles with their popularity, and people by name with their Titles. */
internal class CloudSearch(val titles: List<Pair<TitleId.Tmdb, Double>>, val people: List<Pair<String, List<TitleId.Tmdb>>>)

internal open class CloudException(message: String) : IOException(message)

/** Where the device keeps the token the cloud gave it. */
internal interface DeviceTokenStore {
    fun deviceToken(): String?
    fun saveDeviceToken(token: String)
}

/**
 * The key Viewers of one Provider share file reads under: a SHA-256 of its host, so the cloud
 * never learns the Provider's address.
 */
internal fun providerKey(login: ProviderLogin): String =
    MessageDigest.getInstance("SHA-256").digest(XtreamClient.baseUrl(login.address).host.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
