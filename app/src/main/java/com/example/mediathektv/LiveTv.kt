package com.example.mediathektv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Ein abspielbarer Stream; [licenseUrl] gesetzt, wenn er Widevine-geschützt ist. */
data class Stream(val url: String, val licenseUrl: String? = null)

/**
 * SRF-Livestreams über die offizielle SRG-Schnittstelle ("Integration Layer"):
 * 1. Medienbeschreibung zur URN holen  2. passende Quelle wählen  3. Akamai-Freigabe anhängen.
 * Die Freigabe wird nur für Anschlüsse in der Schweiz ausgestellt.
 */
object SrgResolver {
    private const val IL = "https://il.srgssr.ch/integrationlayer/2.0/mediaComposition/byUrn/"
    private const val TOKEN = "https://tp.srgssr.ch/akahd/token?acl="

    suspend fun resolve(urn: String): Stream = withContext(Dispatchers.IO) {
        val chapter = get("$IL$urn.json").getJSONArray("chapterList").getJSONObject(0)
        val resources = chapter.optJSONArray("resourceList")
        if (resources == null || resources.length() == 0) {
            error(blockText(chapter.optString("blockReason")))
        }
        val all = (0 until resources.length()).map { resources.getJSONObject(it) }
            .filter { it.optString("protocol").startsWith("HLS") }
        // ohne Kopierschutz bevorzugen, dann beste Qualität
        val best = all.sortedWith(compareBy<JSONObject>({ it.has("drmList") }, { qualityRank(it.optString("quality")) }))
            .firstOrNull() ?: error("Kein abspielbarer Stream gefunden.")

        var url = best.getString("url")
        if (best.optString("tokenType") == "AKAMAI") url = withToken(url)
        val license = best.optJSONArray("drmList")?.let { list ->
            (0 until list.length()).map { list.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "WIDEVINE" }?.optString("licenseUrl")
        }
        Stream(url, license)
    }

    // Die Freigabe gilt für die ersten beiden Pfadteile der Stream-Adresse (acl = "/teil1/teil2/" + Stern).
    private fun withToken(url: String): String {
        val parts = url.split('/')
        val acl = "/${parts[3]}/${parts[4]}/*"
        val auth = get(TOKEN + acl).getJSONObject("token").getString("authparams")
        return url + (if ('?' in url) "&" else "?") + auth
    }

    private fun get(url: String): JSONObject =
        Net.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            JSONObject(resp.body!!.string())
        }

    private fun qualityRank(q: String) = when (q) { "HD" -> 0; "HQ" -> 1; "SD" -> 2; else -> 3 }

    private fun blockText(reason: String) = when (reason) {
        "GEOBLOCK" -> "Dieser Sender ist nur in der Schweiz verfügbar."
        "LEGAL" -> "Diese Sendung darf aus rechtlichen Gründen gerade nicht gezeigt werden."
        "STARTDATE", "ENDDATE" -> "Zurzeit nicht verfügbar."
        else -> "Livestream nicht verfügbar."
    }
}
