package com.example.mediathektv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Zugriff auf die MediathekViewWeb-API (Index aller öffentlich-rechtlichen Mediatheken).
 * Hinweis: Die Suche dort ist unscharf (Wortsuche). Darum wird hier grob gesucht
 * und anschließend exakt nach Rubrik, Sender und Dauer gefiltert.
 */
object Api {
    private const val ENDPOINT = "https://mediathekviewweb.de/api/query"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Nicht-deutschsprachige Fassungen und Kinderprogramm ausblenden. */
    private val excludedChannels = setOf("ARTE.FR", "ARTE.EN", "ARTE.ES", "ARTE.IT", "ARTE.PL", "KiKA", "ZDF-tivi")

    suspend fun search(
        text: String = "",
        topic: String = "",
        channel: String = "",
        size: Int = 100,
        minSeconds: Int? = null,
        maxSeconds: Int? = null,
    ): List<Video> = withContext(Dispatchers.IO) {
        val queries = JSONArray()
        fun add(fields: List<String>, value: String) =
            queries.put(JSONObject().put("fields", JSONArray(fields)).put("query", value))
        if (text.isNotBlank()) add(listOf("title", "topic"), text)
        if (topic.isNotBlank()) add(listOf("topic"), topic)
        if (channel.isNotBlank()) add(listOf("channel"), channel)

        val body = JSONObject()
            .put("queries", queries)
            .put("sortBy", "timestamp").put("sortOrder", "desc")
            .put("future", false).put("offset", 0).put("size", size)
            .apply {
                minSeconds?.let { put("duration_min", it) }
                maxSeconds?.let { put("duration_max", it) }
            }
        val request = Request.Builder().url(ENDPOINT)
            .post(body.toString().toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Server antwortet mit ${resp.code}")
            val results = JSONObject(resp.body!!.string())
                .optJSONObject("result")?.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i -> parse(results.getJSONObject(i)) }
        }
    }

    private fun parse(o: JSONObject): Video? {
        val channel = o.optString("channel")
        val url = o.optString("url_video_hd").ifBlank { o.optString("url_video") }
        if (url.isBlank() || channel in excludedChannels) return null
        val raw = o.optString("title")
        val topic = o.optString("topic")
        return Video(
            id = o.optString("id"),
            channel = channel,
            topic = topic,
            title = Meta.stripTopic(Meta.displayTitle(raw), topic),
            rawTitle = raw,
            description = o.optString("description"),
            duration = o.optInt("duration"),
            timestamp = o.optLong("timestamp"),
            url = url,
            subtitleUrl = o.optString("url_subtitle"),
            language = Meta.language(raw),
        )
    }
}

/** Stellt die Inhalte für die Startseite zusammen. */
object Catalog {

    // ---------- Filme ----------
    // Exakte Film-Rubriken der Sender (aus der Datenquelle ermittelt).
    private val filmTopics = setOf(
        "Filme in der ARD", "FilmMittwoch im Ersten", "Film im NDR",          // ARD + Dritte
        "Filme", "Spielfilm-Highlights", "Herzkino", "Das kleine Fernsehspiel", // ZDF, ZDFneo
        "Kino - Filme", "Fernsehfilme und Serien - Fernsehfilme",               // ARTE
        "Spielfilm", "Fernsehfilm",                                             // 3sat
        "Film", "Schweizer Film",                                               // SRF, SWR
    )
    private val filmQueries = listOf("Filme in der ARD", "Filme", "Kino - Filme", "Spielfilm",
        "Fernsehfilm", "Film", "Das kleine Fernsehspiel", "Herzkino", "Schweizer Film")

    suspend fun films(): List<Video> = collect(filmQueries, minSeconds = 60 * 60) { it.topic in filmTopics }

    // ---------- Krimi ----------
    private val crimeTopic = Regex(
        "^(Tatort|Tatort aus Österreich|Polizeiruf 110|LandKrimi|Wilsberg|Ein starkes Team|" +
            "Der Kommissar und .*|.*-Krimi|Ein Krimi aus .*|Der Krimi .*)$"
    )
    private val crimeQueries = listOf("Tatort", "Polizeiruf 110", "Krimi", "LandKrimi", "Wilsberg", "Ein starkes Team")

    suspend fun crime(): List<Video> = collect(crimeQueries, minSeconds = 60 * 60) { crimeTopic.matches(it.topic) }

    // ---------- Dokus ----------
    private val docTopic = Regex(
        "^(Dokumentationen|Dokumentarfilm|Dokumentarfilmzeit|DOK|Kulturdoku|Universum|Universum History|" +
            "Terra X|Terra X History|Expeditionen|Expeditionen ins Tierreich|Expedition in die Heimat|" +
            "Dokus & Reportagen im BR|Doku & Reportage|ZDFinfo Doku|" +
            "Entdeckung der Welt - .*|Wissenschaft - .*|Geschichte - .*)$"
    )
    private val docQueries = listOf("Dokumentationen", "Dokumentarfilm", "Doku", "Universum", "Terra X",
        "Expedition", "Entdeckung der Welt", "Geschichte", "Wissenschaft")

    suspend fun docs(): List<Video> = collect(docQueries, minSeconds = 40 * 60) { docTopic.matches(it.topic) }

    // ---------- Serien ----------
    /** Sammel-Rubriken, in denen der Serienname im Titel steht (z.B. ARTE). */
    private val genericSeriesTopics = setOf("Fernsehfilme und Serien - Serien", "Serien")

    /** Formate mit Folgennummer, die aber keine Serien sind (Nachrichten, Talk, Magazine, Shows). */
    private val notSeries = Regex(
        "journal|magazin|kulturzeit|\\bnano\\b|hallo deutschland|bares für rares|küchenschlacht|illner|lanz|" +
            "tagesschau|tagesthemen|\\bheute\\b|nachrichten|\\bnews\\b|aktuell|\\bsport|quiz|talk|\\bshow\\b|" +
            "yoga|ernährungs|werwölfe|let's talk|wetter|börse|gottesdienst|landesschau|hier und heute",
        RegexOption.IGNORE_CASE
    )

    private fun seriesName(v: Video): String? =
        (if (v.topic in genericSeriesTopics) Meta.seriesNameFromTitle(v.rawTitle) else v.topic)
            ?.let { Meta.cleanSeriesName(it) }?.ifBlank { null }

    /** Rubriken, die Filme/Dokus sammeln, sind keine Serien. */
    private fun isSeriesTopic(topic: String) =
        !notSeries.containsMatchIn(topic) && topic !in filmTopics && !docTopic.matches(topic)

    suspend fun series(): List<Series> = coroutineScope {
        val recent = async { Api.search(size = 1000, minSeconds = 20 * 60, maxSeconds = 70 * 60) }
        val arte = async { Api.search(topic = "Fernsehfilme und Serien - Serien", size = 200, minSeconds = 20 * 60) }
        val episodes = (recent.await() + arte.await())
            .filter { it.episode != null && isSeriesTopic(it.topic) }

        groupVersions(episodes)
            .groupBy { seriesName(it) }
            .filterKeys { it != null }
            .filterValues { it.size >= 2 }
            .map { (name, eps) ->
                Series(
                    name = name!!,
                    channel = preferredChannel(eps.map { it.channel }),
                    episodes = sortEpisodes(dedupeAcrossChannels(eps)),
                )
            }
            .sortedByDescending { s -> s.episodes.maxOf { it.timestamp } }
            .take(40)
    }

    /** Alle verfügbaren Folgen einer Serie nachladen. */
    suspend fun episodes(series: Series): List<Video> {
        val found = Api.search(text = series.name, size = 400, minSeconds = 10 * 60)
            .filter { seriesName(it) == series.name }
        val all = groupVersions(found).ifEmpty { series.episodes }
        return sortEpisodes(dedupeAcrossChannels(all))
    }

    suspend fun search(text: String): List<Video> =
        dedupeAcrossChannels(groupVersions(Api.search(text = text, size = 150, minSeconds = 5 * 60)))

    /** Sammel-Rubrik (z.B. "Filme in der ARD") statt eines echten Reihen-/Seriennamens? */
    fun isRubric(topic: String) = topic in filmTopics || docTopic.matches(topic) || topic in genericSeriesTopics

    // ---------- Hilfsfunktionen ----------

    /** Grob suchen (parallel), exakt filtern, Fassungen bündeln, Dopplungen entfernen. */
    private suspend fun collect(
        queries: List<String>,
        minSeconds: Int,
        keep: (Video) -> Boolean,
    ): List<Video> = coroutineScope {
        val raw = queries.map { q -> async { runCatching { Api.search(topic = q, size = 150, minSeconds = minSeconds) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
            .filter(keep)
        dedupeAcrossChannels(groupVersions(raw)).sortedByDescending { it.timestamp }.take(60)
    }

    /** Fasst Sprach-/Hörfassungen derselben Sendung zusammen; die deutsche wird Hauptfassung. */
    private fun groupVersions(list: List<Video>): List<Video> =
        list.distinctBy { it.id }
            .groupBy { "${it.channel}|${it.topic}|${Meta.baseTitle(it.rawTitle).lowercase()}" }
            .values.map { group ->
                val main = group.firstOrNull { it.language == "Deutsch" } ?: group.first()
                val others = group.filter { it.id != main.id }.distinctBy { it.language }
                    .filter { it.language != main.language }
                main.copy(variants = others)
            }

    /** Gleiche Sendung auf mehreren Sendern (z.B. Tatort in ARD und NDR) nur einmal zeigen. */
    private fun dedupeAcrossChannels(list: List<Video>): List<Video> =
        list.groupBy { normalize(it.topic) + "|" + normalize(it.title) + "|" + (it.episode?.sortKey ?: "") }
            .values.map { same -> same.minBy { channelRank(it.channel) } }

    private fun sortEpisodes(list: List<Video>): List<Video> =
        list.sortedWith(compareBy<Video> { it.episode?.sortKey ?: Int.MAX_VALUE }.thenBy { it.timestamp })

    private val channelOrder = listOf("ARD", "ZDF", "ARTE.DE", "3Sat", "ZDFneo", "ONE", "SRF", "ORF")
    private fun channelRank(ch: String) = channelOrder.indexOf(ch).let { if (it < 0) channelOrder.size else it }
    private fun preferredChannel(chs: List<String>) = chs.minBy { channelRank(it) }
    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
}
