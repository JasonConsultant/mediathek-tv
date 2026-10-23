package com.example.mediathektv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Vollbild-Player.
 * Fernbedienung (Bedienleiste ausgeblendet): OK = Pause/Weiter, Links/Rechts = 10 s zurück/vor,
 * Untertitel-Taste = Untertitel an/aus. Bei eingeblendeter Leiste gilt deren normale Steuerung
 * (dort gibt es auch den Untertitel-Knopf). Zurück = beenden.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var hasSubtitles = false
    private var subtitlesOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        hasSubtitles = subtitleUrl.isNotBlank()
        subtitlesOn = hasSubtitles && intent.getBooleanExtra(EXTRA_SUBTITLE_ON, false)

        player = ExoPlayer.Builder(this).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true                     // kein Bildschirmschoner während der Wiedergabe
            setShowSubtitleButton(hasSubtitles)
            controllerShowTimeoutMs = 3500
        }
        setContentView(view)

        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (hasSubtitles) {
            // ARD/ZDF/ORF/SRF liefern EBU-TT bzw. TTML; vereinzelt WebVTT.
            val mime = if (subtitleUrl.substringBefore('?').endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_TTML
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(mime)
                        .setLanguage("de")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        player.setMediaItem(item.build())
        applySubtitles()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Video kann nicht abgespielt werden (evtl. nicht mehr verfügbar oder nur im Inland abrufbar).",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        })
        player.prepare()
        player.playWhenReady = true
    }

    private fun applySubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage("de")
            .build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS && hasSubtitles) {
                subtitlesOn = !subtitlesOn
                applySubtitles()
                Toast.makeText(this, if (subtitlesOn) "Untertitel an" else "Untertitel aus", Toast.LENGTH_SHORT).show()
                return true
            }
            // Nur wenn die Bedienleiste nicht sichtbar ist: Schnellsteuerung
            if (!view.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (player.isPlaying) player.pause() else player.play()
                        view.showController(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); return true }
                }
            }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
        view.showController()
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); if (::player.isInitialized) player.release() }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_SUBTITLE_ON = "subtitleOn"

        fun start(ctx: Context, video: Video, subtitlesOn: Boolean) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_URL, video.url)
                    .putExtra(EXTRA_TITLE, video.title)
                    .putExtra(EXTRA_SUBTITLE, video.subtitleUrl)
                    .putExtra(EXTRA_SUBTITLE_ON, subtitlesOn)
            )
        }
    }
}
