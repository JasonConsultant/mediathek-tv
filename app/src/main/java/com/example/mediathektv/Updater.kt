package com.example.mediathektv

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Update(val versionName: String, val versionCode: Int, val apkUrl: String)

/**
 * Prüft beim Start die GitHub-Releases des Repos (öffentlich, ohne Zugangsdaten).
 * Release-Tags haben die Form "v1.<Build-Nummer>"; die Build-Nummer ist der versionCode.
 */
object Updater {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body!!.string())
                val tag = json.getString("tag_name")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
                if (code <= BuildConfig.VERSION_CODE) return@runCatching null
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@runCatching null
                Update(tag.removePrefix("v"), code, apk.getString("browser_download_url"))
            }
        }.getOrNull()
    }

    /** Lädt die APK und öffnet den System-Installer. Gibt false zurück, wenn der Download scheitert. */
    suspend fun downloadAndInstall(ctx: Context, update: Update): Boolean {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val target = File(dir, "MediathekTV-${update.versionName}.apk")
                client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                target
            }.getOrNull()
        } ?: return false

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }
}
