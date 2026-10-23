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

data class Update(val versionName: String, val versionCode: Int, val apkUrl: String)

/** Prüft GitHub Releases auf eine neuere Version und installiert sie. */
object Updater {
    private val client = OkHttpClient()
    private val token: String get() = BuildConfig.GH_TOKEN
    private fun auth(b: Request.Builder) = if (token.isNotBlank()) b.header("Authorization", "Bearer $token") else b

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val req = auth(Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json"))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body!!.string())
                val tag = json.getString("tag_name")            // z.B. v1.17
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
                if (code <= BuildConfig.VERSION_CODE) return@withContext null
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
                Update(tag.removePrefix("v"), code, apk.getString("url"))  // API-URL: funktioniert auch bei privatem Repo
            }
        }.getOrNull()
    }

    suspend fun downloadAndInstall(ctx: Context, update: Update) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "MediathekTV-${update.versionName}.apk")
        val req = auth(Request.Builder().url(update.apkUrl).header("Accept", "application/octet-stream")).build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Download fehlgeschlagen: ${resp.code}" }
            resp.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
    }
}
