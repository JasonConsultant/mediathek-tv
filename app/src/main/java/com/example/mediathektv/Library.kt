package com.example.mediathektv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Wie weit eine Sendung geschaut wurde. Positionen in Millisekunden. */
data class Progress(
    val key: String,
    val position: Long,
    val duration: Long,
    val updated: Long,
    val video: Video,
    val seriesId: String? = null,
    val seriesName: String? = null,
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** Eintrag der Merkliste: entweder eine Sendung oder eine Serie/Reihe. */
data class ListEntry(val key: String, val added: Long, val video: Video? = null, val series: SeriesInfo? = null)

/**
 * Persönliche Daten auf dem Gerät: Weiterschauen, Gesehen-Haken, Merkliste.
 * Compose-Zustand, damit sich Startseite und Kacheln sofort aktualisieren (auch nach dem Player).
 */
object Library {
    private const val WATCHED_RATIO = 0.92f      // ab hier gilt eine Sendung als gesehen
    private const val MAX_WATCHED = 5000
    private val writer = Executors.newSingleThreadExecutor()
    private var file: File? = null

    var progress by mutableStateOf<Map<String, Progress>>(emptyMap()); private set
    var watched by mutableStateOf<Set<String>>(emptySet()); private set      // LinkedHashSet: älteste zuerst
    var list by mutableStateOf<List<ListEntry>>(emptyList()); private set     // neueste zuerst
    var broken by mutableStateOf<Set<String>>(emptySet()); private set        // beim Sender gelöscht

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "library.json")
        runCatching { load(file!!.readText()) }
    }

    // ---------- Abfragen ----------

    fun isWatched(v: Video) = v.stableKey in watched
    fun isBroken(v: Video) = v.stableKey in broken
    fun markBroken(v: Video) {
        if (v.stableKey in broken) return
        broken = LinkedHashSet((broken.toList().takeLast(MAX_WATCHED - 1) + v.stableKey))
        progress = progress - v.stableKey
        persist()
    }
    fun progressOf(v: Video): Progress? = progress[v.stableKey]

    /** Für die Reihe "Weiterschauen": angefangene Sendungen und vorgemerkte nächste Folgen. */
    fun continueWatching(): List<Progress> = progress.values.sortedByDescending { it.updated }.take(40)

    fun inList(key: String) = list.any { it.key == key }
    fun seriesKey(s: SeriesInfo) = "series:${s.id}"

    // ---------- Ändern ----------

    /** Vom Player alle paar Sekunden aufgerufen. */
    fun saveProgress(v: Video, position: Long, duration: Long, seriesId: String?, seriesName: String?) {
        if (duration <= 0 || position < 5_000) return
        if (position.toFloat() / duration >= WATCHED_RATIO) { markWatched(v, true); return }
        progress = progress + (v.stableKey to Progress(v.stableKey, position, duration, now(), v, seriesId, seriesName))
        persist()
    }

    fun markWatched(v: Video, value: Boolean) {
        val key = v.stableKey
        watched = if (value) LinkedHashSet((watched - key).toList().takeLast(MAX_WATCHED - 1) + key) else watched - key
        progress = progress - key
        persist()
    }

    /** Nächste Folge in "Weiterschauen" vormerken, wenn die vorige zu Ende geschaut wurde. */
    fun queueNext(next: Video, seriesId: String?, seriesName: String?) {
        val key = next.stableKey
        if (key in watched || key in progress) return
        progress = progress + (key to Progress(key, 0, next.duration * 1000L, now(), next, seriesId, seriesName))
        persist()
    }

    fun removeProgress(v: Video) { progress = progress - v.stableKey; persist() }

    fun toggle(v: Video) = toggle(ListEntry(v.stableKey, now(), video = v))
    fun toggle(s: SeriesInfo) = toggle(ListEntry(seriesKey(s), now(), series = s))

    private fun toggle(entry: ListEntry) {
        list = if (inList(entry.key)) list.filter { it.key != entry.key } else listOf(entry) + list
        persist()
    }

    // ---------- Speichern / Laden ----------

    private fun now() = System.currentTimeMillis()

    private fun persist() {
        val f = file ?: return
        val json = toJson()
        writer.execute {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
        }
    }

    private fun toJson(): String = JSONObject()
        .put("progress", JSONArray().apply {
            progress.values.forEach { p ->
                put(JSONObject().put("k", p.key).put("p", p.position).put("d", p.duration).put("u", p.updated)
                    .putOpt("sid", p.seriesId).putOpt("sn", p.seriesName).put("v", videoJson(p.video)))
            }
        })
        .put("watched", JSONArray(watched.toList()))
        .put("broken", JSONArray(broken.toList()))
        .put("list", JSONArray().apply {
            list.forEach { e ->
                put(JSONObject().put("k", e.key).put("a", e.added)
                    .putOpt("v", e.video?.let { videoJson(it) }).putOpt("s", e.series?.let { seriesJson(it) }))
            }
        })
        .toString()

    private fun load(text: String) {
        val o = JSONObject(text)
        progress = o.optJSONArray("progress").objects().mapNotNull { p ->
            runCatching {
                Progress(p.getString("k"), p.getLong("p"), p.getLong("d"), p.getLong("u"),
                    CatalogParser.parseVideo(p.getJSONObject("v").toString()),
                    p.optString("sid").ifBlank { null }, p.optString("sn").ifBlank { null })
            }.getOrNull()
        }.associateBy { it.key }
        watched = LinkedHashSet(o.optJSONArray("watched")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        broken = LinkedHashSet(o.optJSONArray("broken")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        list = o.optJSONArray("list").objects().mapNotNull { e ->
            runCatching {
                ListEntry(e.getString("k"), e.getLong("a"),
                    e.optJSONObject("v")?.let { CatalogParser.parseVideo(it.toString()) },
                    e.optJSONObject("s")?.let { CatalogParser.parseSeries(it.toString()) })
            }.getOrNull()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** Gleiche Schlüssel wie im Katalog, damit [CatalogParser] die Einträge wieder einlesen kann. */
    internal fun videoJson(v: Video): JSONObject = JSONObject()
        .put("i", v.id).put("c", v.channel).put("t", v.topic).put("n", v.title).put("d", v.description.take(400))
        .put("s", v.duration).put("ts", v.timestamp).put("u", v.url).put("l", v.language)
        .apply {
            if (v.subtitleUrl.isNotBlank()) put("ut", v.subtitleUrl)
            if (v.fallbackUrl.isNotBlank()) put("u2", v.fallbackUrl)
            if (v.source.geo.isNotBlank()) put("g", v.source.geo)
            v.year?.let { put("y", it) }
            v.genre?.let { put("ge", it) }
            v.episode?.let { e -> e.season?.let { put("se", it) }; put("ep", e.number) }
            if (v.firstSeen > 0) put("fs", v.firstSeen)
            v.image?.let { put("im", it) }
            if (v.variants.isNotEmpty()) put("v", JSONArray().apply {
                v.variants.forEach { x ->
                    put(JSONObject().put("l", x.language).put("u", x.url).apply {
                        if (x.subtitleUrl.isNotBlank()) put("ut", x.subtitleUrl)
                        if (x.fallbackUrl.isNotBlank()) put("u2", x.fallbackUrl)
                        if (x.source.geo.isNotBlank()) put("g", x.source.geo)
                    })
                }
            })
        }

    private fun seriesJson(s: SeriesInfo): JSONObject = JSONObject()
        .put("id", s.id).put("n", s.name).put("c", s.channel)
        .put("k", when (s.kind) { SeriesKind.FILM_SERIES -> "filmreihe"; SeriesKind.DOC_SERIES -> "doku"; else -> "serie" })
        .put("cr", if (s.crime) 1 else 0).put("cnt", s.count).put("new", s.latest).put("d", s.description)
        .put("av", JSONObject(s.availability as Map<*, *>)).put("nf", s.newestEpisode).put("nn", s.newEpisodes)
        .apply { s.image?.let { put("im", it) } }
}
