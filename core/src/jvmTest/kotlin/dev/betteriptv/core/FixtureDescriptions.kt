package dev.betteriptv.core

import com.squareup.moshi.Moshi

/**
 * What a Prefix table makes of a set of Listings, one tab-separated line each, so two tables can be
 * compared: the fixture's movie and show Listings, plus one Listing per Prefix, Category hint and
 * Channel quality word the table had before the Base/Provider split (`snapshots/split-inputs.txt`).
 */
internal object FixtureDescriptions {
    private val json = Moshi.Builder().build().adapter(Any::class.java)

    private fun resource(path: String) = FixtureDescriptions::class.java.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private fun read(file: String): List<Map<*, *>> = (json.fromJson(resource("/fixture/$file")) as List<*>).filterIsInstance<Map<*, *>>()

    /** Each Listing as (name, Category). */
    fun inputs(): List<Pair<String, String>> {
        val fixture = listOf("vod_streams.json" to "vod_categories.json", "series_streams.json" to "series_categories.json").flatMap { (streams, categories) ->
            val names = read(categories).associate { it["category_id"].toString() to it["category_name"].toString() }
            read(streams).map { it["name"].toString() to names[it["category_id"].toString()].orEmpty() }
        }
        val synthetic = resource("/snapshots/split-inputs.txt").lineSequence().filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        return fixture + synthetic
    }

    fun render(table: PrefixTable): String = inputs().joinToString("") { (name, category) ->
        val d = table.describe(name, category)
        val a = d.attributes
        val channel = table.channelName(name)
        listOf(
            name, category,
            "unknown=${d.unknownPrefix}", "hidden=${d.hidden}",
            "language=${a.language?.value}", "subtitles=${a.subtitles?.value?.sorted()}", "quality=${a.quality?.value}",
            "hdr=${a.hdr?.value}", "source=${a.source?.value}", "dolby=${a.dolbyAudio}", "cam=${a.cam}", "ad=${a.audioDescription}",
            "live=${table.describeLive(name, category)}", "channel=${channel.key}|${channel.quality}|${channel.hevc}",
            "clutterCategory=${table.isClutterCategory(category)}", "genre=${table.genreRank(category)}",
        ).joinToString("\t", postfix = "\n")
    }
}
