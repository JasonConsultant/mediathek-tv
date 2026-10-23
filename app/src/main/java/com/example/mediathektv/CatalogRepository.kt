package com.example.mediathektv

import android.content.Context
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Inhalt der Startseite aus dem täglich erzeugten Katalog (siehe tools/build_catalog.py). */
data class CatalogIndex(
    val generated: Long,
    val films: List<Video>,
    val crime: List<Video>,
    val docs: List<Video>,
    val series: List<SeriesInfo>,
    val filmSeries: List<SeriesInfo>,
    val docSeries: List<SeriesInfo>,
    val live: List<LiveChannel> = emptyList(),
)

/**
 * Lädt den Katalog von GitHub (Branch "catalog") und hält eine Kopie auf dem Gerät,
 * damit die App sofort startet und nur bei Änderungen neu lädt.
 */
class CatalogRepository(context: Context) {
    private val base = "https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO}/catalog"
    private val indexFile = File(context.filesDir, "catalog/index.json.gz")
    private val etagFile = File(context.filesDir, "catalog/index.etag")
    private val seriesDir = File(context.cacheDir, "series")

    /** Zeitpunkt, zu dem der gespeicherte Katalog zuletzt geschrieben wurde (auch vom Hintergrund-Job). */
    fun storedAt(): Long = indexFile.lastModified()

    /** Zuletzt gespeicherter Katalog (ohne Netz), oder null. */
    suspend fun cached(): CatalogIndex? = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) null
        else runCatching { indexFile.inputStream().use { CatalogParser.parseIndex(it) } }.getOrNull()
    }

    /** Neuen Katalog holen. null = unverändert oder nicht erreichbar. */
    suspend fun refresh(): CatalogIndex? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$base/index.json.gz").apply {
                if (indexFile.exists() && etagFile.exists()) header("If-None-Match", etagFile.readText())
            }.build()
            Net.client.newCall(request).execute().use { resp ->
                if (resp.code == 304) return@runCatching null
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                indexFile.parentFile?.mkdirs()
                val tmp = File.createTempFile("index", ".tmp", indexFile.parentFile)
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val parsed = tmp.inputStream().use { CatalogParser.parseIndex(it) }   // erst prüfen, dann übernehmen
                synchronized(CatalogRepository::class.java) {
                    if (!tmp.renameTo(indexFile)) { tmp.copyTo(indexFile, overwrite = true); tmp.delete() }
                    resp.header("ETag")?.let { etagFile.writeText(it) }
                }
                parsed
            }
        }.getOrNull()
    }

    /** Folgen einer Serie (aus dem Netz, sonst aus dem Zwischenspeicher). */
    suspend fun episodes(id: String): List<Video> = withContext(Dispatchers.IO) {
        val file = File(seriesDir, "$id.json.gz")
        val fresh = runCatching {
            Net.client.newCall(Request.Builder().url("$base/s/$id.json.gz").build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                seriesDir.mkdirs()
                resp.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }.isSuccess
        if (!fresh && !file.exists()) return@withContext emptyList()
        runCatching { file.inputStream().use { CatalogParser.parseEpisodes(it) } }.getOrDefault(emptyList())
    }
}

/** Ermittelt das Land des Internetanschlusses – danach richten sich die Geo-Sperren der Sender. */
object CountryDetector {
    suspend fun detect(): String = withContext(Dispatchers.IO) {
        val viaIp = runCatching {
            Net.client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { resp ->
                resp.body!!.string().lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()
            }
        }.getOrNull()
        viaIp?.takeIf { it.length == 2 } ?: Locale.getDefault().country.takeIf { it in Geo.countries } ?: "DE"
    }
}

/** Liest das Katalogformat stückweise (wenig Speicher – wichtig auf TV-Boxen). */
object CatalogParser {

    fun parseIndex(input: InputStream): CatalogIndex = reader(input).use { r ->
        var generated = 0L
        var films = emptyList<Video>(); var crime = emptyList<Video>(); var docs = emptyList<Video>()
        var series = emptyList<SeriesInfo>(); var filmSeries = emptyList<SeriesInfo>(); var docSeries = emptyList<SeriesInfo>()
        var live = emptyList<LiveChannel>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "generated" -> generated = r.nextLong()
                "films" -> films = videos(r)
                "crime" -> crime = videos(r)
                "docs" -> docs = videos(r)
                "series" -> series = seriesList(r)
                "filmSeries" -> filmSeries = seriesList(r)
                "docSeries" -> docSeries = seriesList(r)
                "live" -> live = liveList(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        CatalogIndex(generated, films, crime, docs, series, filmSeries, docSeries, live)
    }

    fun parseEpisodes(input: InputStream): List<Video> = reader(input).use { r ->
        var eps = emptyList<Video>()
        r.beginObject()
        while (r.hasNext()) if (r.nextName() == "e") eps = videos(r) else r.skipValue()
        r.endObject()
        eps
    }

    /** Eine einzelne Sendung/Serie im Katalogformat (für gespeicherte Bibliothekseinträge). */
    fun parseVideo(json: String): Video = JsonReader(java.io.StringReader(json)).use { video(it) }
    fun parseSeries(json: String): SeriesInfo = JsonReader(java.io.StringReader("[$json]")).use { seriesList(it).first() }

    private fun liveList(r: JsonReader): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        r.beginArray()
        while (r.hasNext()) {
            var name = ""; var ch = ""; var url = ""; var alt = ""; var urn = ""
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "n" -> name = r.nextString(); "c" -> ch = r.nextString(); "u" -> url = r.nextString()
                "alt" -> alt = r.nextString(); "srg" -> urn = r.nextString()
                else -> r.skipValue()
            }
            r.endObject()
            out.add(LiveChannel(name, ch, url, alt, urn))
        }
        r.endArray()
        return out
    }

    /** Akzeptiert gzip-komprimierte und unkomprimierte Daten. */
    private fun reader(input: InputStream): JsonReader {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered
        return JsonReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    private fun videos(r: JsonReader): List<Video> {
        val out = ArrayList<Video>()
        r.beginArray()
        while (r.hasNext()) out.add(video(r))
        r.endArray()
        return out
    }

    private fun video(r: JsonReader): Video {
        var id = ""; var ch = ""; var topic = ""; var title = ""; var desc = ""
        var secs = 0; var ts = 0L; var url = ""; var sub = ""; var geo = ""; var url2 = ""
        var year: Int? = null; var genre: String? = null; var season: Int? = null; var ep: Int? = null; var fs = 0L
        var variants = emptyList<Pair<String, Source>>(); var alts = emptyList<Source>(); var lang = "Deutsch"; var image: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "i" -> id = r.nextString()
                "c" -> ch = r.nextString()
                "t" -> topic = r.nextString()
                "n" -> title = r.nextString()
                "d" -> desc = r.nextString()
                "s" -> secs = r.nextInt()
                "ts" -> ts = r.nextLong()
                "u" -> url = r.nextString()
                "u2" -> url2 = r.nextString()
                "ut" -> sub = r.nextString()
                "g" -> geo = r.nextString()
                "y" -> year = r.nextInt()
                "ge" -> genre = r.nextString()
                "se" -> season = r.nextInt()
                "ep" -> ep = r.nextInt()
                "fs" -> fs = r.nextLong()
                "l" -> lang = r.nextString()
                "im" -> image = r.nextString()
                "v" -> variants = list(r) { lang, src -> lang to src }
                "a" -> alts = list(r) { _, src -> src }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val main = Video(
            id = id, channel = ch, topic = topic, title = title, description = desc, duration = secs,
            timestamp = ts, source = Source(url, sub, geo, url2), year = year, genre = genre,
            episode = ep?.let { Episode(season, it) }, alternatives = alts, firstSeen = fs, language = lang, image = image,
        )
        return main.copy(variants = variants.mapIndexed { i, (lang, src) ->
            main.copy(id = "$id-$i", language = lang, source = src, alternatives = emptyList())
        })
    }

    /** Liste von {l?, u, ut?, g?} */
    private fun <T> list(r: JsonReader, make: (String, Source) -> T): List<T> {
        val out = ArrayList<T>()
        r.beginArray()
        while (r.hasNext()) {
            var lang = ""; var url = ""; var sub = ""; var geo = ""; var url2 = ""
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "l" -> lang = r.nextString(); "u" -> url = r.nextString(); "u2" -> url2 = r.nextString()
                "ut" -> sub = r.nextString(); "g" -> geo = r.nextString()
                else -> r.skipValue()
            }
            r.endObject()
            out.add(make(lang, Source(url, sub, geo, url2)))
        }
        r.endArray()
        return out
    }

    private fun seriesList(r: JsonReader): List<SeriesInfo> {
        val out = ArrayList<SeriesInfo>()
        r.beginArray()
        while (r.hasNext()) {
            var id = ""; var name = ""; var ch = ""; var kind = SeriesKind.SERIES; var crime = false
            var count = 0; var latest = 0L; var desc = ""; val av = HashMap<String, Int>(); var nf = 0L; var nn = 0
            var image: String? = null
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "id" -> id = r.nextString()
                "n" -> name = r.nextString()
                "c" -> ch = r.nextString()
                "k" -> kind = when (r.nextString()) { "filmreihe" -> SeriesKind.FILM_SERIES; "doku" -> SeriesKind.DOC_SERIES; else -> SeriesKind.SERIES }
                "cr" -> crime = r.nextInt() == 1
                "cnt" -> count = r.nextInt()
                "new" -> latest = r.nextLong()
                "d" -> desc = r.nextString()
                "nf" -> nf = r.nextLong()
                "nn" -> nn = r.nextInt()
                "im" -> image = r.nextString()
                "av" -> {
                    r.beginObject()
                    while (r.hasNext()) av[r.nextName()] = r.nextInt()
                    r.endObject()
                }
                else -> r.skipValue()
            }
            r.endObject()
            out.add(SeriesInfo(id, name, ch, kind, crime, count, latest, desc, av, nf, nn, image))
        }
        r.endArray()
        return out
    }
}
