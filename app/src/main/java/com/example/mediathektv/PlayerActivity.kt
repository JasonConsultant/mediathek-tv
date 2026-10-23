package com.example.mediathektv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Vollbild-Player mit Untertiteln (falls vorhanden).
 * Fernbedienung: OK/Play = Play/Pause, Links/Rechts = ±10 s,
 * Untertitel-Taste (Captions) oder "U" = Untertitel an/aus, Zurück = beenden.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var subtitlesOn = false
    private var hasSubtitles = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""
        val subUrl = intent.getStringExtra("sub") ?: ""
        subtitlesOn = intent.getBooleanExtra("subOn", false)
        hasSubtitles = subUrl.isNotBlank()

        player = ExoPlayer.Builder(this).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3000
        }
        setContentView(view)

        val builder = MediaItem.Builder().setUri(url).setMediaId(title)
        if (hasSubtitles) {
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(if (subUrl.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_TTML)
                .setLanguage("de")
                .setSelectionFlags(if (subtitlesOn) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
                .build()
            builder.setSubtitleConfigurations(listOf(sub))
        }
        player.setMediaItem(builder.build())
        applySubtitles()
        player.prepare()
        player.playWhenReady = true
    }

    private fun applySubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitlesOn)
            .build()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (player.isPlaying) player.pause() else player.play()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                player.seekTo(player.currentPosition + 10_000)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_U -> {
                if (hasSubtitles) { subtitlesOn = !subtitlesOn; applySubtitles() }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        view.showController()
        return true
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }

    companion object {
        fun start(ctx: Context, v: Video, subtitlesOn: Boolean) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
                .putExtra("url", v.url).putExtra("title", v.title)
                .putExtra("sub", v.subtitleUrl).putExtra("subOn", subtitlesOn))
        }
    }
}
