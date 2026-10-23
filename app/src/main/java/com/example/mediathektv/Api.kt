package com.example.mediathektv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Video(
    val id: String,
    val channel: String,
    val topic: String,
    val title: String,
    val description: String,
    val duration: Int,          // Sekunden
    val timestamp: Long,        // Unix-Sekunden
    val url: String,            // bestes Video (MP4 oder HLS)
    val website: String
)

/** Abfrage der MediathekViewWeb-API – bündelt ARD, ZDF, Dritte, ARTE, 3sat, phoenix usw. */
object Api {
    private val client = OkHttpClient()
    private const val ENDPOINT = "https://mediathekviewweb.de/api/query"

    suspend fun query(
        text: String = "",
        channels: List<String> = emptyList(),
        size: Int = 40,
        sortBy: String = "timestamp"
    ): List<Video> = withContext(Dispatchers.IO) {
        val queries = JSONArray()
        if (text.isNotBlank()) {
            queries.put(JSONObject().put("fields", JSONArray(listOf("title", "topic"))).put("query", text))
        }
        channels.forEach { ch ->
            queries.put(JSONObject().put("fields", JSONArray(listOf("channel"))).put("query", ch))
        }
        val body = JSONObject()
            .put("queries", queries)
            .put("sortBy", sortBy)
            .put("sortOrder", "desc")
            .put("future", false)
            .put("offset", 0)
            .put("size", size)
            .put("duration_min", 300)   // keine Kurz-Clips
            .toString()

        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Content-Type", "text/plain")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: return@withContext emptyList())
            val results = json.optJSONObject("result")?.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).map { i ->
                val o = results.getJSONObject(i)
                val best = o.optString("url_video_hd").ifBlank { o.optString("url_video") }
                Video(
                    id = o.optString("id"),
                    channel = o.optString("channel"),
                    topic = o.optString("topic"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    duration = o.optInt("duration"),
                    timestamp = o.optLong("timestamp"),
                    url = best,
                    website = o.optString("url_website")
                )
            }.filter { it.url.isNotBlank() }
        }
    }
}
