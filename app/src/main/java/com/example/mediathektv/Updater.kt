package com.example.mediathektv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Update(val versionName: String, val versionCode: Int, val apkUrl: String)

/**
 * Prüft beim Start die GitHub-Releases des Repos (öffentlich, ohne Zugangsdaten).
 * Release-Tags haben die Form "v2-<Build-Nummer>"; die Build-Nummer ist der versionCode.
 * (Ältere Releases "v1.<n>" waren mit einem anderen Schlüssel signiert und werden ignoriert.)
 */
object Updater {
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            Net.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body!!.string())
                val tag = json.getString("tag_name")
                val code = Regex("^v2-(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching null
                if (code <= BuildConfig.VERSION_CODE) return@runCatching null
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == ASSET_NAME } ?: return@runCatching null
                val url = apk.getString("browser_download_url")
                if (!url.startsWith("https://github.com/${BuildConfig.GITHUB_REPO}/")) return@runCatching null
                Update("2.$code", code, url)
            }
        }.getOrNull()
    }

    private const val ASSET_NAME = "MediathekTV.apk"
    private const val MAX_APK_BYTES = 40L * 1024 * 1024
    private val allowedHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    /**
     * Lädt die APK und öffnet den System-Installer. Vorher wird geprüft, dass die Datei wirklich diese App ist:
     * gleicher Paketname, gleicher Signierschlüssel wie die installierte Version, passende Versionsnummer.
     * Gibt false zurück, wenn der Download scheitert oder die Prüfung fehlschlägt.
     */
    suspend fun downloadAndInstall(ctx: Context, update: Update): Boolean {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val target = File(dir, "MediathekTV-${update.versionName}.apk")
                val req = Request.Builder().url(update.apkUrl).build()
                Net.client.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    check(resp.request.url.isHttps && resp.request.url.host in allowedHosts) { "unerwartete Download-Adresse" }
                    resp.body!!.byteStream().use { input -> target.outputStream().use { copyLimited(input, it, MAX_APK_BYTES) } }
                }
                check(sameApp(ctx, target, update.versionCode)) { "APK gehört nicht zu dieser App" }
                target
            }.onFailure { runCatching { File(ctx.cacheDir, "updates").listFiles()?.forEach { f -> f.delete() } } }.getOrNull()
        } ?: return false

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }

    /** Paketname, Versionsnummer und Signierzertifikat der heruntergeladenen APK gegen die installierte App prüfen. */
    private fun sameApp(ctx: Context, apk: File, expectedCode: Int): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (info.packageName != ctx.packageName) return false
        @Suppress("DEPRECATION")
        if (info.versionCode != expectedCode) return false
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        return certs(info).isNotEmpty() && certs(info) == certs(mine)
    }

    private fun certs(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                   else @Suppress("DEPRECATION") info.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }
}

/** Kopiert höchstens [max] Bytes; mehr gilt als Fehler (Schutz vor Endlos-Downloads). */
internal fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, max: Long) {
    val buf = ByteArray(64 * 1024); var total = 0L
    while (true) {
        val n = input.read(buf); if (n < 0) break
        total += n; check(total <= max) { "Datei zu groß" }
        out.write(buf, 0, n)
    }
}
