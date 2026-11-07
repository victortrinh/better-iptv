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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

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
            found = (answer["titles"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { title -> titleId(title)?.let { it to json.serializeNulls().toJson(title) } }.toMap(),
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

    /** Reports the Prefixes a Sync on [provider] found that the Prefix table doesn't know, at most [MAX_PREFIX_BATCH]. */
    fun reportUnknownPrefixes(provider: String, prefixes: List<UnknownPrefix>) {
        require(prefixes.size <= MAX_PREFIX_BATCH)
        val body = mapOf(
            "provider" to provider,
            "prefixes" to prefixes.map { mapOf("prefix" to it.prefix, "listingCount" to it.listingCount, "example" to it.example) },
        )
        authorized { token -> post("v1/unknown-prefixes", json.toJson(body), token) }
    }

    /**
     * The Provider table the cloud keeps for [provider] (ADR 0014), as text in the Base table's format:
     * empty for a Provider it has never seen. Waits at most [callTimeout], since a Sync waits for it.
     */
    fun providerTable(provider: String, callTimeout: Duration): String =
        authorized { token -> post("v1/provider-table", json.toJson(mapOf("provider" to provider)), token, callTimeout, text = true) } as String

    /**
     * The subtitles to download for a Movie, or a Show's [episode], in [languages] (ISO 639-1, at
     * most [MAX_SUBTITLE_LANGUAGES]): the best few in each language, best first.
     */
    fun searchSubtitles(id: TitleId.Tmdb, episode: EpisodeNumber?, languages: List<String>): List<SubtitleOffer> {
        require(languages.size in 1..MAX_SUBTITLE_LANGUAGES)
        val body = mapOf(
            "kind" to kindName(id.kind),
            "tmdbId" to id.tmdbId,
            "season" to episode?.season,
            "episode" to episode?.number,
            "languages" to languages,
        )
        val answer = authorized { token -> post("v1/subtitles", json.serializeNulls().toJson(body), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return (answer["subtitles"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { offer ->
            SubtitleOffer(
                id = offer["id"] as? String ?: return@mapNotNull null,
                language = offer["language"] as? String ?: return@mapNotNull null,
                release = offer["release"] as? String ?: "",
                hearingImpaired = offer["hearingImpaired"] == true,
            )
        }
    }

    /**
     * The Episodes Show [tmdbId] has yet to air, as TMDB lists them, from the next one to the end of
     * its season, named in [language] (ISO 639-1).
     */
    fun upcoming(tmdbId: Long, language: String): List<UpcomingEpisode> {
        val answer = authorized { token -> post("v1/upcoming", json.toJson(mapOf("tmdbId" to tmdbId, "language" to language)), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return (answer["episodes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { episode ->
            UpcomingEpisode(
                EpisodeNumber((episode["season"] as? Number)?.toInt() ?: return@mapNotNull null, (episode["episode"] as? Number)?.toInt() ?: return@mapNotNull null),
                airDate = (episode["airDate"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null,
                name = episode["name"] as? String,
            )
        }
    }

    /**
     * Season [season] of Show [tmdbId] as TMDB lists it, each Episode with its name in [language]
     * (ISO 639-1), its still (a TMDB path) and its runtime, for the Episode picker.
     */
    fun season(tmdbId: Long, season: Int, language: String): List<SeasonEpisode> {
        val answer = authorized { token -> post("v1/season", json.toJson(mapOf("tmdbId" to tmdbId, "season" to season, "language" to language)), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return (answer["episodes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { episode ->
            SeasonEpisode(
                (episode["episode"] as? Number)?.toInt() ?: return@mapNotNull null,
                still = (episode["stillPath"] as? String)?.takeIf { it.startsWith("/") },
                runtimeMinutes = (episode["runtimeMinutes"] as? Number)?.toInt()?.takeIf { it > 0 },
            )
        }
    }

    /**
     * The Charts for [region] (ISO 3166, `CA`) in [language] (ISO 639-1), as the cloud's JSON, which
     * [Charts] reads; kept whole, so parts a later app reads are already there.
     */
    fun charts(region: String, language: String): String {
        val answer = authorized { token -> post("v1/charts", json.toJson(mapOf("region" to region, "language" to language)), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return json.serializeNulls().toJson(answer)
    }

    /**
     * The Games of [leagues] (by the ids the cloud's schedule knows, cloud/src/schedule.ts) for
     * today and the week ahead, as the cloud's JSON, which [Games] reads; kept whole, so parts a
     * later app reads are already there. A League the cloud has no copy of yet is absent from it.
     * The request is given [callTimeout] in all, so a screen reading behind it isn't held long.
     */
    fun games(leagues: List<String>, callTimeout: Duration): String {
        val answer = authorized { token -> post("v1/games", json.toJson(mapOf("leagues" to leagues)), token, callTimeout) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        return json.serializeNulls().toJson(answer)
    }

    /** The subtitle file a search offered as [id]; null when its service no longer has it. */
    fun downloadSubtitle(id: String): DownloadedSubtitle? {
        val answer = try {
            authorized { token -> post("v1/subtitles/download", json.toJson(mapOf("id" to id)), token) } as? Map<*, *>
        } catch (_: NotFound) {
            return null
        } ?: throw CloudException("The cloud sent something other than an object.")
        val format = answer["format"] as? String
        val text = answer["text"] as? String
        if (format == null || text == null) throw CloudException("The cloud sent a subtitle without its format or text.")
        return DownloadedSubtitle(format, text)
    }

    /**
     * A fresh Sign-in code for this device to show, with the sign-in page's address for its QR
     * (ADR 0015). It replaces the code the device had.
     */
    fun startSignIn(model: String? = null): SignInCode {
        val body = if (model == null) "{}" else json.toJson(mapOf("model" to model))
        val answer = authorized { token -> post("v1/sign-in", body, token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        val code = answer["code"] as? String
        val url = answer["url"] as? String
        val expiresIn = (answer["expiresIn"] as? Number)?.toLong()
        if (code == null || url == null || expiresIn == null || expiresIn <= 0) throw CloudException("The cloud sent a Sign-in code without its address or lifetime.")
        return SignInCode(code, url, Duration.ofSeconds(expiresIn))
    }

    /** The email of the Account this device is signed in to, or null when it is signed in to none. */
    fun account(): String? {
        val answer = authorized { token -> post("v1/account", "{}", token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        if (!answer.containsKey("email")) throw CloudException("The cloud didn't say which Account this device is signed in to.")
        return answer["email"] as? String
    }

    /**
     * Pushes [changes] (at most [MAX_HISTORY_BATCH], each with its Profile's Account id) and
     * [entries] (as many) to the Account, saying the device is on Profile [current], and pulls what
     * changed since [cursor] (#435, #440): one request, one answer. Throws [NotSignedIn] when the
     * device isn't signed in.
     */
    fun sync(cursor: Long, provider: String?, current: String?, changes: List<Pair<String, HistoryChange>>, entries: List<SyncEntry>): SyncAnswer {
        require(changes.size <= MAX_HISTORY_BATCH && entries.size <= MAX_HISTORY_BATCH)
        val body = mapOf(
            "cursor" to cursor,
            "provider" to provider,
            "current" to current,
            "progress" to changes.map { (profile, it) ->
                mapOf(
                    "profile" to profile, "title" to historyKey(it.id), "season" to it.episode?.season, "episode" to it.episode?.number, "position" to it.position,
                    "duration" to it.duration, "watched" to it.watched, "removed" to it.removed, "at" to it.at.toEpochMilli(),
                )
            },
            "entries" to entries.map { mapOf("kind" to it.kind, "profile" to it.profile, "key" to it.key, "value" to it.value, "at" to it.at.toEpochMilli()) },
        )
        val answer = authorized { token -> post("v1/sync", json.serializeNulls().toJson(body), token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        val next = (answer["cursor"] as? Number)?.toLong() ?: throw CloudException("The cloud's sync answer has no cursor.")
        return SyncAnswer(
            next,
            answer["reset"] == true,
            (answer["progress"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { change ->
                val profile = change["profile"] as? String ?: return@mapNotNull null
                val id = (change["title"] as? String)?.let(::historyTitleId) ?: return@mapNotNull null
                val season = (change["season"] as? Number)?.toInt()
                val number = (change["episode"] as? Number)?.toInt()
                profile to HistoryChange(
                    id,
                    if (season != null && number != null) EpisodeNumber(season, number) else null,
                    (change["position"] as? Number)?.toLong() ?: return@mapNotNull null,
                    (change["duration"] as? Number)?.toLong(),
                    change["watched"] == true,
                    change["removed"] == true,
                    Instant.ofEpochMilli((change["at"] as? Number)?.toLong() ?: return@mapNotNull null),
                )
            },
            (answer["entries"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { entry ->
                SyncEntry(
                    entry["kind"] as? String ?: return@mapNotNull null,
                    entry["profile"] as? String,
                    entry["key"] as? String ?: return@mapNotNull null,
                    entry["value"] as? String,
                    Instant.ofEpochMilli((entry["at"] as? Number)?.toLong() ?: return@mapNotNull null),
                )
            },
        )
    }

    /** The devices signed in to this one's Account (#434), by sign-in; none when it is signed in to none. */
    fun accountDevices(): List<AccountDevice> {
        val answer = authorized { token -> post("v1/account/devices", "{}", token) } as? Map<*, *>
            ?: throw CloudException("The cloud sent something other than an object.")
        val devices = answer["devices"] as? List<*> ?: throw CloudException("The cloud sent no device list.")
        return devices.map { device ->
            device as? Map<*, *> ?: throw CloudException("The cloud sent a device that isn't an object.")
            val id = device["id"] as? String
            val signedInAt = (device["signedInAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (id == null || signedInAt == null) throw CloudException("The cloud sent a device without its id or sign-in date.")
            AccountDevice(id, device["model"] as? String, signedInAt, device["thisDevice"] == true)
        }
    }

    /**
     * Signs the device listed as [id] out of this one's Account, or this device without one. A device
     * no longer on the Account counts as signed out.
     */
    fun signOut(id: String? = null) {
        val body = if (id == null) "{}" else json.toJson(mapOf("device" to id))
        try {
            authorized { token -> post("v1/account/sign-out", body, token) }
        } catch (_: NotFound) {
        }
    }

    /**
     * Told after each answer whether the cloud has this device signed in to an Account, with when its
     * request was sent ([System.nanoTime]), so an answer to a request sent before a change can be told apart.
     */
    @Volatile var onAccountHeard: ((signedIn: Boolean, sentAt: Long) -> Unit)? = null

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

    /** POSTs [body] to [path] and reads the answer as JSON, or with [text] as text. */
    private fun post(path: String, body: String, token: String?, callTimeout: Duration? = null, text: Boolean = false): Any? {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .post(body.toRequestBody(JSON))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        // A client with a call timeout shares the pool and dispatcher of the one it is built from.
        val client = if (callTimeout == null) http else http.newBuilder().callTimeout(callTimeout).build()
        val sentAt = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw Unauthorized()
                when (response.header(ACCOUNT_HEADER)) {
                    "signed-in" -> onAccountHeard?.invoke(true, sentAt)
                    "none" -> onAccountHeard?.invoke(false, sentAt)
                }
                if (response.code == 404) throw NotFound()
                if (response.code == 403) throw NotSignedIn()
                if (!response.isSuccessful) throw CloudException("The cloud answered HTTP ${response.code}.")
                if (response.code == 204) return null
                if (text) return response.body!!.string()
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

    private class NotFound : CloudException("The cloud has no such thing.")

    /** The cloud answered that this device is signed in to no Account: it was signed out from elsewhere. */
    class NotSignedIn : CloudException("This device isn't signed in to an Account.")

    companion object {
        /** The most Titles one metadata request may ask for. */
        const val MAX_BATCH = 50

        /** The most Listings one match request may ask about. */
        const val MAX_MATCH_BATCH = 25

        /** The most history changes one sync may push (cloud/src/sync.ts). */
        const val MAX_HISTORY_BATCH = 500

        /** The most unknown Prefixes one report may hold. */
        const val MAX_PREFIX_BATCH = 200
        /** The answer header saying whether the cloud has this device signed in: `signed-in` or `none`. */
        private const val ACCOUNT_HEADER = "Zugig-Account"
        private val JSON = "application/json".toMediaType()
        private val json = Moshi.Builder().build().adapter(Any::class.java)

        private fun kindName(kind: TitleKind) = when (kind) {
            TitleKind.Movie -> "movie"
            TitleKind.Show -> "show"
        }
    }
}

/** What a history sync answered: the [cursor] to ask from next, and the Account's [changes] since (all of them when [reset]). */
/** What a sync answered: the cursor it is at now, whether it is everything ([reset]), and what changed, each change with its Profile's Account id. */
internal class SyncAnswer(val cursor: Long, val reset: Boolean, val changes: List<Pair<String, HistoryChange>>, val entries: List<SyncEntry>)

/** What the cloud answered: each found Title's metadata as JSON, and the Titles TMDB doesn't have. */
internal class CloudMetadata(val found: Map<TitleId.Tmdb, String>, val notFound: List<TitleId.Tmdb>)

/**
 * A Sign-in code for the TV to show (ADR 0015): the phone opens [url] (the QR) or types the code on
 * the sign-in page, for [expiresIn] from when it was given; then the device asks for a new one.
 */
data class SignInCode(val code: String, val url: String, val expiresIn: Duration)

/**
 * A device signed in to the Account (#434): named by its [model] ("Fire TV Stick 4K Max", or null
 * when it sent none), signed in at [signedInAt]; [thisDevice] is the one asking.
 */
data class AccountDevice(val id: String, val model: String?, val signedInAt: Instant, val thisDevice: Boolean)

/** A subtitle file the cloud found for a Movie or Episode, in [language] (ISO 639-1), named by its [release]. */
data class SubtitleOffer(val id: String, val language: String, val release: String, val hearingImpaired: Boolean)

/** A downloaded subtitle file: its [text], in its [format] (`srt`, `vtt` or `ass`). */
class DownloadedSubtitle(val format: String, val text: String)

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

/** An Episode TMDB lists as not aired yet: it airs on [airDate], and TMDB may know its [name]. */
internal class UpcomingEpisode(val episode: EpisodeNumber, val airDate: LocalDate, val name: String?)

/** An Episode of a season as TMDB lists it: its [number], its [still] (a TMDB path) and its runtime, each null when TMDB has none. */
internal class SeasonEpisode(val number: Int, val still: String?, val runtimeMinutes: Int?)
