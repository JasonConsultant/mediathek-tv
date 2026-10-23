package com.example.mediathektv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Vollbild-Player. Fernbedienung: OK/Play = Play/Pause, Links/Rechts = ±10 s, Zurück = beenden. */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""

        player = ExoPlayer.Builder(this).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            controllerShowTimeoutMs = 3000
        }
        setContentView(view)

        player.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(title).build())
        player.prepare()
        player.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (player.isPlaying) player.pause() else player.play()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                player.seekTo(player.currentPosition + 10_000)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
            else -> return super.onKeyDown(keyCode, event)
        }
        view.showController()
        return true
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }

    companion object {
        fun start(ctx: Context, v: Video) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
                .putExtra("url", v.url).putExtra("title", v.title))
        }
    }
}
