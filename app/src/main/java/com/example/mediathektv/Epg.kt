package com.example.mediathektv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Eine Sendung im Programmführer. Zeiten in Unix-Sekunden. */
data class Program(val start: Long, val stop: Long, val title: String, val subtitle: String, val description: String) {
    fun progress(now: Long) = ((now - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    val times: String get() = "${hhmm(start)}–${hhmm(stop)}"
}

/**
 * Programmführer für die Live-Sender (aus tools/build_epg.py, alle 3 Stunden neu).
 * Als Compose-Zustand, damit Senderliste und Player ihn direkt nutzen können.
 */
object Epg {
    var byChannel by mutableStateOf<Map<String, List<Program>>>(emptyMap()); private set

    fun now(channel: String, t: Long = nowSec()): Program? = byChannel[channel]?.firstOrNull { it.start <= t && t < it.stop }
    fun upcoming(channel: String, count: Int, t: Long = nowSec()): List<Program> =
        byChannel[channel].orEmpty().filter { it.start > t }.take(count)

    private const val URL_PATH = "epg.json.gz"

    /** Gespeicherten Stand laden und, wenn es einen neuen gibt, aus dem Netz holen. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "catalog/epg.json.gz")
        val etagFile = File(context.filesDir, "catalog/epg.etag")
        if (byChannel.isEmpty() && file.exists()) runCatching { byChannel = parse(file) }
        runCatching {
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO}/catalog/$URL_PATH")
                .apply { if (file.exists() && etagFile.exists()) header("If-None-Match", etagFile.readText()) }
                .build()
            Net.client.newCall(request).execute().use { resp ->
                if (resp.code == 304 || !resp.isSuccessful) return@use
                file.parentFile?.mkdirs()
                val tmp = File.createTempFile("epg", ".tmp", file.parentFile)
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val parsed = parse(tmp)
                if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
                resp.header("ETag")?.let { etagFile.writeText(it) }
                byChannel = parsed
            }
        }
    }

    /** {"generated":…, "ch": {"Name": [[start, stop, titel, untertitel, text], …]}} */
    internal fun parse(file: File): Map<String, List<Program>> =
        JsonReader(InputStreamReader(GZIPInputStream(file.inputStream()), Charsets.UTF_8)).use { r ->
            val out = HashMap<String, List<Program>>()
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "ch") { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    val list = ArrayList<Program>()
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val p = Program(r.nextLong(), r.nextLong(), r.nextString(), r.nextString(), r.nextString())
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                        list.add(p)
                    }
                    r.endArray()
                    out[name] = list
                }
                r.endObject()
            }
            r.endObject()
            out
        }
}

fun hhmm(ts: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ts * 1000))
