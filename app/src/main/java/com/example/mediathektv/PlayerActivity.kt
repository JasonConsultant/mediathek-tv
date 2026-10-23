package com.example.mediathektv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/** Was abgespielt werden soll – im Speicher, weil Folgen-Listen für Intents zu groß werden können. */
object Playback {
    var video: Video? = null
    var queue: List<Video> = emptyList()       // folgende Folgen (für "nächste Folge")
    var subtitles = false
    var fromStart = false
    var seriesId: String? = null
    var seriesName: String? = null
    var live: LiveChannel? = null
}

/**
 * Vollbild-Player für Sendungen und Live-TV.
 * Fernbedienung (Bedienleiste ausgeblendet): OK = Pause/Weiter, Links/Rechts = 10 s, Untertitel-Taste = an/aus.
 * Sendungen: setzt an der gespeicherten Stelle fort, speichert den Fortschritt und startet am Ende
 * nach 10 Sekunden die nächste Folge (OK = sofort, Zurück = beenden).
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var video: Video? = null
    private var live: LiveChannel? = null
    private var triedAlternative = false
    private var hasSubtitles = false
    private var subtitlesOn = false
    private var countdown = 0

    private val saveTicker = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 10_000) }
    }
    private val countdownTicker = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) playNext() else { showNextOverlay(); handler.postDelayed(this, 1000) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(applicationContext)

        player = ExoPlayer.Builder(this).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            controllerShowTimeoutMs = 3500
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(36, 24, 36, 24)
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(0xE016213A.toInt()) }
            visibility = TextView.GONE
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlayParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlayParams.gravity = Gravity.BOTTOM or Gravity.END
        overlayParams.setMargins(0, 0, 64, 96)
        root.addView(overlay, overlayParams)
        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(error: PlaybackException) = onError()
        })

        val liveChannel = Playback.live
        val v = Playback.video
        when {
            liveChannel != null -> startLive(liveChannel)
            v != null -> startVideo(v, Playback.fromStart)
            else -> finish()     // App wurde im Player neu gestartet – nichts zu spielen
        }
    }

    // ---------------------------------------------------------------- Sendungen

    private fun startVideo(v: Video, fromStart: Boolean) {
        video = v
        live = null
        hasSubtitles = v.hasSubtitles
        subtitlesOn = hasSubtitles && Playback.subtitles
        view.setShowSubtitleButton(hasSubtitles)
        overlay.visibility = TextView.GONE

        val item = MediaItem.Builder().setUri(v.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
        if (hasSubtitles) {
            val mime = if (v.subtitleUrl.substringBefore('?').endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_TTML
            item.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(v.subtitleUrl))
                    .setMimeType(mime).setLanguage("de").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            ))
        }
        player.setMediaItem(item.build())
        applySubtitles()

        val saved = Library.progressOf(v)
        if (!fromStart && saved != null && saved.position > 30_000 && saved.fraction < 0.95f) {
            player.seekTo(saved.position)
            toast("Fortsetzen bei ${clock(saved.position)}")
        }
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(saveTicker)
        handler.postDelayed(saveTicker, 10_000)
    }

    private fun saveProgress() {
        val v = video ?: return
        if (player.duration > 0) Library.saveProgress(v, player.currentPosition, player.duration, Playback.seriesId, Playback.seriesName)
    }

    private fun onEnded() {
        val v = video ?: return finish()
        Library.markWatched(v, true)
        val next = Playback.queue.firstOrNull() ?: return finish()
        Library.queueNext(next, Playback.seriesId, Playback.seriesName)
        countdown = 10
        showNextOverlay()
        handler.postDelayed(countdownTicker, 1000)
    }

    private fun showNextOverlay() {
        val next = Playback.queue.firstOrNull() ?: return
        val label = next.episode?.label?.let { "$it · " }.orEmpty()
        overlay.text = "Nächste Folge in $countdown s\n$label${next.title}\nOK = jetzt abspielen · Zurück = beenden"
        overlay.visibility = TextView.VISIBLE
    }

    private fun playNext() {
        handler.removeCallbacks(countdownTicker)
        val next = Playback.queue.firstOrNull() ?: return finish()
        Playback.queue = Playback.queue.drop(1)
        Playback.video = next
        startVideo(next, fromStart = false)
    }

    private fun cancelCountdown(): Boolean {
        if (overlay.visibility != TextView.VISIBLE) return false
        handler.removeCallbacks(countdownTicker)
        finish()
        return true
    }

    // ---------------------------------------------------------------- Live-TV

    private fun startLive(ch: LiveChannel) {
        live = ch
        video = null
        view.setShowSubtitleButton(false)
        if (ch.srgUrn.isNotBlank()) {
            toast("${ch.name} wird geladen …")
            lifecycleScope.launch {
                runCatching { SrgResolver.resolve(ch.srgUrn) }
                    .onSuccess { playStream(it, ch.name) }
                    .onFailure { toast(it.message ?: "Livestream nicht verfügbar."); finish() }
            }
        } else playStream(Stream(ch.url), ch.name)
    }

    private fun playStream(stream: Stream, title: String) {
        val item = MediaItem.Builder().setUri(stream.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        stream.licenseUrl?.let { item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(it).build()) }
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
    }

    // ---------------------------------------------------------------- Fehler, Untertitel, Tasten

    private fun onError() {
        val ch = live
        if (ch != null && ch.altUrl.isNotBlank() && !triedAlternative) {   // z.B. internationale Fassung von Das Erste
            triedAlternative = true
            playStream(Stream(ch.altUrl), ch.name)
            return
        }
        toast(if (ch != null) "${ch.name} ist in deinem Land gerade nicht verfügbar."
              else "Video kann nicht abgespielt werden (evtl. nicht mehr verfügbar oder nur im Inland abrufbar).")
        finish()
    }

    private fun applySubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage("de")
            .build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (overlay.visibility == TextView.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY -> { playNext(); return true }
                    KeyEvent.KEYCODE_BACK -> return cancelCountdown()
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS && hasSubtitles) {
                subtitlesOn = !subtitlesOn
                applySubtitles()
                toast(if (subtitlesOn) "Untertitel an" else "Untertitel aus")
                return true
            }
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
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, end))
        view.showController()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player.pause()
        val ctx = applicationContext
        Thread { WatchNext.sync(ctx) }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        if (isFinishing) Playback.live = null
    }

    companion object {
        fun play(
            ctx: Context, video: Video, subtitles: Boolean,
            queue: List<Video> = emptyList(), seriesId: String? = null, seriesName: String? = null, fromStart: Boolean = false,
        ) {
            Playback.live = null
            Playback.video = video
            Playback.queue = queue
            Playback.subtitles = subtitles
            Playback.fromStart = fromStart
            Playback.seriesId = seriesId
            Playback.seriesName = seriesName
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }

        fun playLive(ctx: Context, channel: LiveChannel) {
            Playback.live = channel
            Playback.video = null
            Playback.queue = emptyList()
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }
    }
}
