package com.example.mediathektv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gemeinsamer HTTP-Client der App. */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

/**
 * Live-Suche über den kompletten Index (MediathekViewWeb-API) – findet auch, was nicht im
 * Katalog ist. Die API liefert keine Geo-Freigabe; solche Treffer sind mit geoKnown = false markiert.
 */
object LiveSearch {
    private const val ENDPOINT = "https://mediathekviewweb.de/api/query"
    private val excludedChannels = setOf("ARTE.FR", "ARTE.EN", "ARTE.ES", "ARTE.IT", "ARTE.PL", "KiKA", "ZDF-tivi")

    suspend fun search(text: String, size: Int = 150): List<Video> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("queries", JSONArray().put(JSONObject().put("fields", JSONArray(listOf("title", "topic"))).put("query", text)))
            .put("sortBy", "timestamp").put("sortOrder", "desc")
            .put("future", false).put("offset", 0).put("size", size)
            .put("duration_min", 5 * 60)
        val request = Request.Builder().url(ENDPOINT)
            .post(body.toString().toRequestBody("text/plain".toMediaType())).build()
        val raw = Net.client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val results = JSONObject(resp.body!!.string()).optJSONObject("result")?.optJSONArray("results")
                ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { parse(results.getJSONObject(it)) }
        }
        bundle(raw)
    }

    private class Hit(val video: Video, val rawTitle: String)

    private fun parse(o: JSONObject): Hit? {
        val channel = o.optString("channel")
        val url = o.optString("url_video_hd").ifBlank { o.optString("url_video") }
        if (url.isBlank() || channel in excludedChannels) return null
        val raw = o.optString("title")
        val topic = o.optString("topic")
        val desc = o.optString("description")
        return Hit(
            Video(
                id = o.optString("id"), channel = channel, topic = topic,
                title = Meta.stripTopic(Meta.displayTitle(raw), topic),
                description = desc, duration = o.optInt("duration"), timestamp = o.optLong("timestamp"),
                source = Source(url, o.optString("url_subtitle")), language = Meta.language(raw),
                year = Meta.year(raw, desc), genre = Meta.genre(raw, desc), episode = Meta.episode(raw, topic),
                geoKnown = false,
            ),
            raw,
        )
    }

    /** Sprachfassungen bündeln und gleiche Sendung auf mehreren Sendern nur einmal zeigen. */
    private fun bundle(hits: List<Hit>): List<Video> {
        val versions = hits.distinctBy { it.video.id }
            .groupBy { "${it.video.channel}|${it.video.topic}|${Meta.baseTitle(it.rawTitle).lowercase()}" }
            .values.map { group ->
                val main = group.firstOrNull { it.video.language == "Deutsch" } ?: group.first()
                main.video.copy(variants = group.filter { it !== main }.map { it.video }
                    .distinctBy { it.language }.filter { it.language != main.video.language })
            }
        return versions
            .groupBy { norm(it.topic) + "|" + norm(it.title) + "|" + (it.episode?.sortKey ?: "") }
            .values.map { same -> same.minBy { channelRank(it.channel) } }
    }

    private val channelOrder = listOf("ARD", "ZDF", "ARTE.DE", "3Sat", "ZDFneo", "ONE", "SRF", "ORF")
    private fun channelRank(ch: String) = channelOrder.indexOf(ch).let { if (it < 0) channelOrder.size else it }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
}
