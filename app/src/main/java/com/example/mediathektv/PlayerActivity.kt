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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.Tracks
import android.app.AlertDialog
import java.util.Locale
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    var liveSubtitles = false                      // Untertitel im Live-TV nur auf Wunsch
    var channels: List<LiveChannel> = emptyList()   // Senderliste zum Umschalten (Reihenfolge wie in der App)
    var playable: Set<String> = emptySet()          // Sender, die hier empfangbar sind

    /** Zustand des Players – für den Geräte-Test (läuft wirklich etwas?). */
    @Volatile var state: String = "idle"
}

/**
 * Vollbild-Player für Sendungen und Live-TV.
 * Fernbedienung (Bedienleiste ausgeblendet): OK = Pause/Weiter, Links/Rechts = 10 s,
 * Hoch / Menü- / Untertitel-Taste = Untertitel wählen oder ausschalten.
 * Live-TV: Hoch/Runter oder Programm +/− = Sender wechseln; Links/Rechts öffnet die Bedienleiste.
 * OK und Medientasten = Pause/Weiter; Info = Jetzt/Danach. Alle Funktionen auch ohne Sondertasten.
 * Sendungen: setzt an der gespeicherten Stelle fort, speichert den Fortschritt und startet am Ende
 * nach 10 Sekunden die nächste Folge (OK = sofort, Zurück = beenden).
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private lateinit var banner: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val remote = RemoteInput()
    private var mediaSession: MediaSession? = null
    private var streamJob: Job? = null
    private var streamGeneration = 0
    private var subtitleDialog: AlertDialog? = null
    private var backgrounded = false
    private var needsLiveRefresh = false
    private var playbackRequested = true

    private var video: Video? = null
    private var live: LiveChannel? = null
    private var triedAlternative = false
    private var hasSubtitles = false
    private var subtitlesOn = false
    private var countdown = 0
    private var countdownPaused = false
    private val hideBanner = Runnable { banner.visibility = TextView.GONE }

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

        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Eigene Kennung je Player: Startet ein neuer Player, bevor der alte zerstört ist (z.B. "Weiterschauen"
        // vom Startbildschirm, während der Player im Hintergrund liegt), würde die gleiche Kennung abstürzen.
        mediaSession = MediaSession.Builder(this, player).setId("player-${java.util.UUID.randomUUID()}").build()
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
        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setLineSpacing(6f, 1f)
            setPadding(40, 28, 40, 28)
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(0xE60B1220.toInt()) }
            visibility = TextView.GONE
        }
        val bannerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bannerParams.gravity = Gravity.BOTTOM
        bannerParams.setMargins(64, 0, 64, 56)
        root.addView(banner, bannerParams)
        setContentView(root)
        view.isFocusable = true
        view.requestFocus()
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                handler.removeCallbacks(hideBanner)
                banner.visibility = View.GONE
            }
        })
        onBackPressedDispatcher.addCallback(this) {
            when {
                overlay.visibility == View.VISIBLE -> cancelCountdown()
                view.isControllerFullyVisible -> { view.hideController(); view.requestFocus() }
                banner.visibility == View.VISIBLE -> { handler.removeCallbacks(hideBanner); hideBanner.run() }
                else -> finish()
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playbackRequested = playWhenReady
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) Playback.state = "playing"
                else if (Playback.state == "playing") Playback.state = "paused"
            }
            override fun onPlayerError(error: PlaybackException) {
                Playback.state = "error:${error.errorCodeName}"
                onError(error)
            }
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
        Playback.state = "loading"
        streamGeneration++
        streamJob?.cancel()
        video = v
        live = null
        handler.removeCallbacks(countdownTicker)
        needsLiveRefresh = false
        hasSubtitles = v.hasSubtitles
        subtitlesOn = Playback.subtitles
        view.setShowSubtitleButton(true)      // auch für Untertitel, die im Stream selbst stecken
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
        player.playWhenReady = !backgrounded
        handler.removeCallbacks(saveTicker)
        if (!backgrounded) handler.postDelayed(saveTicker, 10_000)
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
        countdownPaused = false
        showNextOverlay()
        if (!backgrounded) handler.postDelayed(countdownTicker, 1000)
    }

    private fun showNextOverlay() {
        val next = Playback.queue.firstOrNull() ?: return
        val label = next.episode?.label?.let { "$it · " }.orEmpty()
        val heading = if (countdownPaused) "Nächste Folge bereit" else "Nächste Folge in $countdown s"
        overlay.text = "$heading\n$label${next.title}\nOK = jetzt abspielen · Zurück = beenden"
        overlay.visibility = TextView.VISIBLE
    }

    private fun playNext() {
        countdownPaused = false
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

    private fun startLive(ch: LiveChannel, autoPlay: Boolean = true) {
        Playback.state = "loading"
        streamGeneration++
        val generation = streamGeneration
        streamJob?.cancel()
        needsLiveRefresh = false
        playbackRequested = autoPlay
        player.playWhenReady = autoPlay && !backgrounded
        live = ch
        Playback.live = ch
        video = null
        triedAlternative = false
        // Live: Untertitel aus, bis man sie selbst wählt (Menü-/Untertitel-Taste oder Symbol in der Bedienleiste);
        // sonst schaltet der Player bei Sendern mit deutscher Untertitelspur ungefragt ein.
        subtitlesOn = Playback.liveSubtitles
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage(if (subtitlesOn) "de" else null)
            .build()
        view.setShowSubtitleButton(true)
        view.hideController()
        showBanner()
        if (ch.srgUrn.isNotBlank()) {
            player.stop()
            streamJob = lifecycleScope.launch {
                try {
                    val stream = SrgResolver.resolve(ch.srgUrn)
                    if (generation == streamGeneration && !backgrounded) playStream(stream, ch.name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == streamGeneration && !backgrounded) liveFailed(ch)
                }
            }
        } else playStream(Stream(ch.url), ch.name)
    }

    /** Zum nächsten (+1) oder vorherigen (−1) Sender, der hier empfangbar ist. */
    private fun zap(step: Int) {
        val list = Playback.channels
        val current = live ?: return
        if (list.size < 2) return
        var i = list.indexOfFirst { it.name == current.name }.coerceAtLeast(0)
        repeat(list.size) {
            i = (i + step + list.size) % list.size
            val ch = list[i]
            if (Playback.playable.isEmpty() || ch.name in Playback.playable) { startLive(ch); return }
        }
    }

    /** Infoleiste unten: Nummer, Sender, was gerade läuft und was danach kommt. */
    private fun showBanner(note: String? = null) {
        val ch = live ?: return
        val number = Playback.channels.indexOfFirst { it.name == ch.name }.takeIf { it >= 0 }?.let { "%02d  ".format(it + 1) }.orEmpty()
        val t = nowSec()
        val now = Epg.now(ch.name, t)
        val next = Epg.upcoming(ch.name, 1, t).firstOrNull()
        banner.text = buildString {
            append(number).append(ch.name).append("   ").append(hhmm(t))
            if (now != null) {
                append("\nJetzt  ").append(now.times).append("  ").append(now.title)
                if (now.subtitle.isNotBlank()) append(" – ").append(now.subtitle)
                append("  (").append((now.progress(t) * 100).toInt()).append(" %)")
            }
            if (next != null) append("\nDanach  ").append(next.times).append("  ").append(next.title)
            if (note != null) append("\n").append(note)
            if (Playback.channels.size > 1) append("\n▲▼ Sender wechseln")
            append("  ·  ◀▶ Bedienleiste / Untertitel  ·  OK Pause / Weiter")
        }
        banner.visibility = TextView.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, if (note != null) 8000 else 5000)
    }

    /** Sender lässt sich nicht abspielen: beim Umschalten weiter zappen lassen, sonst schließen. */
    private fun liveFailed(ch: LiveChannel) {
        if (Playback.channels.size > 1) {
            player.stop()
            view.hideController()
            view.requestFocus()
            showBanner("⚠ ${ch.name} kann gerade nicht geladen werden – Verbindung prüfen oder anderen Sender wählen.")
        } else {
            toast("${ch.name} kann gerade nicht geladen werden. Verbindung und regionale Verfügbarkeit prüfen.")
            finish()
        }
    }

    private fun playStream(stream: Stream, title: String) {
        val item = MediaItem.Builder().setUri(stream.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        stream.licenseUrl?.let { item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(it).build()) }
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = playbackRequested && !backgrounded
    }

    // ---------------------------------------------------------------- Fehler, Untertitel, Tasten

    private fun onError(error: PlaybackException? = null) {
        if (backgrounded || isFinishing) return
        val v = video
        val http = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val decoderFailure = error?.errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        if (v != null && decoderFailure && tryVideoFallback(v)) {
            toast("Diese Qualität wird vom Fernseher nicht unterstützt. Ersatzqualität wird versucht.")
            return
        }
        if (v != null && http in listOf(403, 404, 410)) {
            if (tryVideoFallback(v)) return    // HD-Datei weg → Normalqualität
            Library.markBroken(v)
            toast("${v.title}: beim Sender nicht mehr verfügbar.")
            finish(); return
        }
        val ch = live
        if (ch != null && ch.altUrl.isNotBlank() && !triedAlternative) {   // z.B. internationale Fassung von Das Erste
            triedAlternative = true
            playStream(Stream(ch.altUrl), ch.name)
            return
        }
        if (ch != null) return liveFailed(ch)
        toast("Video kann nicht abgespielt werden (evtl. nicht mehr verfügbar oder nur im Inland abrufbar).")
        finish()
    }

    private fun tryVideoFallback(v: Video): Boolean {
        if (v.fallbackUrl.isBlank() || v.fallbackUrl == v.url) return false
        val position = player.currentPosition.coerceAtLeast(0)
        val shouldPlay = player.playWhenReady
        saveProgress()
        val alt = v.copy(source = v.source.copy(url = v.fallbackUrl, fallbackUrl = ""))
        Playback.video = alt
        startVideo(alt, fromStart = true)
        player.seekTo(position)
        player.playWhenReady = shouldPlay && !backgrounded
        return true
    }

    private fun applySubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage("de")
            .build()
    }

    private class TextTrack(val group: Tracks.Group, val index: Int, val label: String)

    /** Alle Untertitel der laufenden Sendung (mitgelieferte und im Stream enthaltene). */
    private fun textTracks(): List<TextTrack> {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        return groups.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { Locale(it).getDisplayLanguage(Locale.GERMAN) }?.takeIf { it.isNotBlank() && it != "und" }
                val name = f.label ?: lang ?: "Untertitel"
                val extra = if ((f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) " (für Hörgeschädigte)" else ""
                TextTrack(g, i, name + extra)
            }
        }.distinctBy { it.label }
    }

    /** Auswahl: Aus oder eine der vorhandenen Untertitel-Spuren. */
    private fun chooseSubtitles() {
        if (subtitleDialog?.isShowing == true) return
        val tracks = textTracks()
        if (tracks.isEmpty()) { toast("Für diese Sendung gibt es keine Untertitel."); return }
        val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val current = if (disabled) 0 else tracks.indexOfFirst { it.group.isTrackSelected(it.index) } + 1
        val labels = (listOf("Aus") + tracks.map { it.label }).toTypedArray()
        view.hideController()
        subtitleDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Untertitel")
            .setSingleChoiceItems(labels, current.coerceAtLeast(0)) { dialog, which ->
                val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (which == 0) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else {
                    val t = tracks[which - 1]
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                player.trackSelectionParameters = params.build()
                subtitlesOn = which != 0
                if (live != null) Playback.liveSubtitles = subtitlesOn   // gilt beim Zappen weiter
                else Playback.subtitles = subtitlesOn                   // gilt auch für die nächste Folge
                dialog.dismiss()
            }
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    subtitleDialog = null
                    remote.reset()
                    if (!backgrounded && !isFinishing) showControls()
                }
                dialog.show()
            }
    }

    private fun showControls() {
        handler.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        view.showController()
        // Fokus immer auf Pause/Weiter – erst nachdem die Leiste wirklich da ist (sonst landet er z.B. auf "+10 s",
        // und OK spult statt zu pausieren; im Geräte-Test so beobachtet).
        val playPause = view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
        val focusPlay = { if (playPause?.isShown == true && playPause.isEnabled) playPause.requestFocus() else view.requestFocus() }
        focusPlay()
        view.post { focusPlay() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.isCanceled) {
            remote.reset()
            return super.dispatchKeyEvent(event)
        }
        val decision = remote.handle(event.keyCode, event.action, event.repeatCount, event.eventTime,
            live != null, view.isControllerFullyVisible, overlay.visibility == View.VISIBLE)
        when (decision.command) {
            RemoteCommand.DELEGATE -> return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
            RemoteCommand.CONSUME -> Unit
            RemoteCommand.TOGGLE_PLAY -> {
                if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else player.play()
                showControls()
            }
            RemoteCommand.PLAY -> { player.play(); showControls() }
            RemoteCommand.PAUSE -> { player.pause(); showControls() }
            RemoteCommand.STOP -> { player.pause(); finish() }
            RemoteCommand.SEEK -> seekBy(decision.seekMs)
            RemoteCommand.PREVIOUS_CHANNEL -> zap(-1)
            RemoteCommand.NEXT_CHANNEL -> zap(1)
            RemoteCommand.INFO -> showBanner()
            RemoteCommand.CONTROLS -> showControls()
            RemoteCommand.SUBTITLES -> chooseSubtitles()
            RemoteCommand.NEXT_EPISODE -> playNext()
        }
        return true
    }

    private fun seekBy(ms: Long) {
        if (player.isCurrentMediaItemSeekable) {
            val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + ms).coerceIn(0, end))
        }
        showControls()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        remote.reset()
        if (hasFocus && ::view.isInitialized && subtitleDialog == null && !backgrounded && !view.hasFocus()) view.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!backgrounded) return
        backgrounded = false
        // Returning from Home/standby stays paused until the user explicitly resumes.
        if (needsLiveRefresh) live?.let { ch ->
            startLive(ch, autoPlay = false)
        }
        showControls()
        handler.removeCallbacks(saveTicker)
        handler.postDelayed(saveTicker, 10_000)
        if (overlay.visibility == View.VISIBLE) showNextOverlay()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onStop() {
        backgrounded = true
        if (overlay.visibility == View.VISIBLE) countdownPaused = true
        needsLiveRefresh = live != null
        streamGeneration++
        streamJob?.cancel()
        remote.reset()
        handler.removeCallbacks(saveTicker)
        handler.removeCallbacks(countdownTicker)
        handler.removeCallbacks(hideBanner)
        saveProgress()
        player.pause()
        super.onStop()
        val ctx = applicationContext
        Thread { WatchNext.sync(ctx) }.start()
    }

    override fun onDestroy() {
        if (isFinishing) Playback.state = "closed"
        streamJob?.cancel()
        subtitleDialog?.setOnDismissListener(null)
        subtitleDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        if (::view.isInitialized) view.player = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
        if (isFinishing) { Playback.live = null; Playback.channels = emptyList() }
    }

    companion object {
        fun play(
            ctx: Context, video: Video, subtitles: Boolean,
            queue: List<Video> = emptyList(), seriesId: String? = null, seriesName: String? = null, fromStart: Boolean = false,
        ) {
            Playback.live = null
            Playback.channels = emptyList()
            Playback.video = video
            Playback.queue = queue
            Playback.subtitles = subtitles
            Playback.fromStart = fromStart
            Playback.seriesId = seriesId
            Playback.seriesName = seriesName
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }

        fun playLive(ctx: Context, channel: LiveChannel, channels: List<LiveChannel> = emptyList(), playable: Set<String> = emptySet()) {
            Playback.live = channel
            Playback.channels = channels
            Playback.playable = playable
            Playback.video = null
            Playback.queue = emptyList()
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }
    }
}
