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
    val topic: String,            // dient als "Rubrik/Genre"
    val title: String,
    val description: String,
    val duration: Int,
    val timestamp: Long,          // Sendedatum
    val url: String,
    val subtitleUrl: String,      // "" wenn keine Untertitel
    val website: String,
    val language: String = "Deutsch",
    val variants: List<Video> = emptyList()  // weitere Sprachfassungen
) {
    /** Produktionsjahr, falls im Titel/Text als 19xx/20xx erkennbar – sonst null. */
    val productionYear: Int? get() =
        Regex("\\b(19|20)\\d{2}\\b").findAll("$title $description")
            .map { it.value.toInt() }.filter { it in 1900..2026 }.maxOrNull()
    val hasSubtitles: Boolean get() = subtitleUrl.isNotBlank()
}

data class Series(val topic: String, val channel: String, val episodes: List<Video>)

object Api {
    private val client = OkHttpClient()
    private const val ENDPOINT = "https://mediathekviewweb.de/api/query"

    suspend fun query(
        text: String = "", channels: List<String> = emptyList(), topicQuery: String = "",
        size: Int = 50, minDuration: Int = 300, sortBy: String = "timestamp"
    ): List<Video> = withContext(Dispatchers.IO) {
        val queries = JSONArray()
        if (text.isNotBlank())
            queries.put(JSONObject().put("fields", JSONArray(listOf("title", "topic"))).put("query", text))
        if (topicQuery.isNotBlank())
            queries.put(JSONObject().put("fields", JSONArray(listOf("topic"))).put("query", topicQuery))
        channels.forEach { ch -> queries.put(JSONObject().put("fields", JSONArray(listOf("channel"))).put("query", ch)) }
        val body = JSONObject().put("queries", queries)
            .put("sortBy", sortBy).put("sortOrder", "desc")
            .put("future", false).put("offset", 0).put("size", size)
            .put("duration_min", minDuration).toString()
        val req = Request.Builder().url(ENDPOINT).post(body.toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: return@withContext emptyList())
            val results = json.optJSONObject("result")?.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).map { i ->
                val o = results.getJSONObject(i)
                val best = o.optString("url_video_hd").ifBlank { o.optString("url_video") }
                Video(
                    id = o.optString("id"), channel = o.optString("channel"),
                    topic = o.optString("topic"), title = o.optString("title"),
                    description = o.optString("description"), duration = o.optInt("duration"),
                    timestamp = o.optLong("timestamp"), url = best,
                    subtitleUrl = o.optString("url_subtitle"),
                    website = o.optString("url_website"),
                    language = detectLanguage(o.optString("title"))
                )
            }.filter { it.url.isNotBlank() }
        }
    }

    private fun detectLanguage(title: String): String = when {
        title.contains("(Englisch", true) || title.contains("(Original", true) -> "Englisch/Original"
        title.contains("(Französisch", true) -> "Französisch"
        else -> "Deutsch"
    }

    suspend fun films(): List<Video> {
        val raw = mutableListOf<Video>()
        for (t in listOf("Spielfilm", "Filme in der ARD", "Fernsehfilm", "Spielfilm-Highlights", "Herzkino"))
            raw += query(topicQuery = t, size = 25, minDuration = 3600)
        return groupLanguages(raw).sortedByDescending { it.timestamp }
    }
    suspend fun crime(): List<Video> {
        val raw = mutableListOf<Video>()
        for (t in listOf("Tatort", "Polizeiruf 110", "Kriminalfilm", "Der Alte", "SOKO"))
            raw += query(topicQuery = t, size = 20, minDuration = 2400)
        return groupLanguages(raw).sortedByDescending { it.timestamp }
    }
    suspend fun docs(): List<Video> =
        groupLanguages(query(channels = listOf("ZDFinfo", "PHOENIX"), size = 40, minDuration = 1500))
            .sortedByDescending { it.timestamp }

    suspend fun series(): List<Series> = withContext(Dispatchers.IO) {
        val raw = query(topicQuery = "Serien", size = 150, minDuration = 900) +
                  query(text = "Folge", size = 120, minDuration = 900)
        groupLanguages(raw).groupBy { it.topic }
            .filter { it.value.size >= 3 && it.key.isNotBlank() }
            .map { (t, eps) -> Series(t, eps.first().channel, eps.sortedByDescending { it.timestamp }) }
            .sortedByDescending { it.episodes.first().timestamp }.take(30)
    }
    suspend fun episodes(topic: String): List<Video> =
        groupLanguages(query(topicQuery = "\"$topic\"", size = 100, minDuration = 600)).sortedBy { it.title }

    /** Gruppiert Sprachfassungen: deutsche Hauptfassung bekommt die anderen als variants. */
    private fun groupLanguages(list: List<Video>): List<Video> {
        val variantRe = Regex("\\s*\\((Audiodeskription|Englisch|Französisch|Original.*?|mit Gebärden.*?|hörgeschädigt.*?|OmU.*?)\\)",
            RegexOption.IGNORE_CASE)
        val groups = LinkedHashMap<String, MutableList<Video>>()
        for (v in list) {
            val base = v.title.replace(variantRe, "").trim()
            if (v.title.contains("Audiodeskription", true) || v.title.contains("Gebärden", true)) continue
            groups.getOrPut("${v.channel}|${v.topic}|$base") { mutableListOf() }.add(v.copy(title = base))
        }
        return groups.values.mapNotNull { g ->
            val main = g.firstOrNull { it.language == "Deutsch" } ?: g.first()
            val others = g.filter { it.id != main.id }
            if (others.isEmpty()) main else main.copy(variants = others)
        }
    }
}
