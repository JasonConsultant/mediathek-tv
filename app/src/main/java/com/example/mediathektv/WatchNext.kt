package com.example.mediathektv

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/**
 * Zeigt "Weiterschauen" auch auf dem Startbildschirm des Fernsehers (Android TV / Google TV).
 * Ein Klick dort öffnet die App über mediathektv://play?key=… und setzt die Sendung fort.
 * Nicht jeder Launcher zeigt fremde Einträge an – Fehler werden deshalb still ignoriert.
 */
object WatchNext {
    private const val MAX = 10
    private const val PREFS = "watchnext"

    fun deepLink(key: String): Uri = Uri.parse("mediathektv://play").buildUpon().appendQueryParameter("key", key).build()

    /** Startbildschirm an "Weiterschauen" der App angleichen. Im Hintergrund aufrufen. */
    @SuppressLint("RestrictedApi")
    fun sync(context: Context) = runCatching {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wanted = Library.continueWatching().take(MAX)
        val wantedKeys = wanted.map { it.key }.toSet()

        // Nicht mehr aktuelle Einträge entfernen
        prefs.all.forEach { (key, id) ->
            if (key !in wantedKeys && id is Long) {
                runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
                prefs.edit().remove(key).apply()
            }
        }

        val poster = Uri.parse("android.resource://${context.packageName}/${R.drawable.watchnext_poster}")
        for (p in wanted) {
            val v = p.video
            val program = WatchNextProgram.Builder()
                .setType(if (v.episode != null) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(if (p.position > 0) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                                  else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                .setLastEngagementTimeUtcMillis(p.updated)
                .setTitle(p.seriesName ?: v.title)
                .apply {
                    if (p.seriesName != null) setEpisodeTitle(Meta.episodeTitle(v.title, p.seriesName))
                    v.episode?.let { e -> setEpisodeNumber(e.number); e.season?.let { setSeasonNumber(it) } }
                }
                .setDescription(v.description.take(200))
                .setPosterArtUri(poster)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setDurationMillis(p.duration.toInt())
                .setLastPlaybackPositionMillis(p.position.toInt())
                .setInternalProviderId(p.key)
                .setIntentUri(deepLink(p.key))
                .build()
            val existing = prefs.getLong(p.key, -1L)
            if (existing >= 0) {
                val updated = resolver.update(TvContractCompat.buildWatchNextProgramUri(existing), program.toContentValues(), null, null)
                if (updated > 0) continue
            }
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())?.let { uri ->
                prefs.edit().putLong(p.key, ContentUris.parseId(uri)).apply()
            }
        }
    }
}
