package com.example.mediathektv

import android.view.KeyEvent

internal enum class RemoteCommand {
    DELEGATE, CONSUME, TOGGLE_PLAY, PLAY, PAUSE, STOP, SEEK,
    PREVIOUS_CHANNEL, NEXT_CHANNEL, INFO, CONTROLS, SUBTITLES, NEXT_EPISODE,
}

internal data class RemoteDecision(val command: RemoteCommand, val seekMs: Long = 0)

/**
 * Own only player shortcuts. Navigation, volume and system keys keep their normal behaviour.
 * A shortcut owns its complete down/up gesture, even if showing the controls changes UI state.
 */
internal class RemoteInput {
    private data class Held(val decision: RemoteDecision, val started: Long, var last: Long)
    private val held = mutableMapOf<Int, Held>()

    fun reset() = held.clear()

    fun handle(
        code: Int, action: Int, repeats: Int, time: Long,
        live: Boolean, controlsVisible: Boolean, countdownVisible: Boolean,
    ): RemoteDecision {
        if (action == KeyEvent.ACTION_UP) {
            return RemoteDecision(if (held.remove(code) != null) RemoteCommand.CONSUME else RemoteCommand.DELEGATE)
        }
        if (action != KeyEvent.ACTION_DOWN) return RemoteDecision(RemoteCommand.DELEGATE)
        if (repeats > 0) {
            val gesture = held[code] ?: return RemoteDecision(RemoteCommand.DELEGATE)
            if (gesture.decision.command != RemoteCommand.SEEK || time - gesture.last < 250) {
                return RemoteDecision(RemoteCommand.CONSUME)
            }
            gesture.last = time
            val step = if (time - gesture.started >= 2000) 30_000L else 10_000L
            return RemoteDecision(RemoteCommand.SEEK, if (gesture.decision.seekMs < 0) -step else step)
        }

        val command = when {
            countdownVisible && code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) -> RemoteCommand.NEXT_EPISODE
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS -> RemoteCommand.SUBTITLES
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteCommand.TOGGLE_PLAY
            code == KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteCommand.PLAY
            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteCommand.PAUSE
            code == KeyEvent.KEYCODE_MEDIA_STOP -> RemoteCommand.STOP
            live && code == KeyEvent.KEYCODE_CHANNEL_UP -> RemoteCommand.PREVIOUS_CHANNEL
            live && code == KeyEvent.KEYCODE_CHANNEL_DOWN -> RemoteCommand.NEXT_CHANNEL
            code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_GUIDE -> if (live) RemoteCommand.INFO else RemoteCommand.CONTROLS
            // Focused controls must receive D-pad events, including key repeats.
            controlsVisible -> RemoteCommand.DELEGATE
            code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> RemoteCommand.TOGGLE_PLAY
            live && code == KeyEvent.KEYCODE_DPAD_UP -> RemoteCommand.PREVIOUS_CHANNEL
            live && code == KeyEvent.KEYCODE_DPAD_DOWN -> RemoteCommand.NEXT_CHANNEL
            live && code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) -> RemoteCommand.CONTROLS
            !live && code == KeyEvent.KEYCODE_DPAD_UP -> RemoteCommand.SUBTITLES
            code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) -> RemoteCommand.SEEK
            else -> RemoteCommand.DELEGATE
        }
        val decision = RemoteDecision(command, if (command == RemoteCommand.SEEK) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000
        } else 0)
        if (command != RemoteCommand.DELEGATE) held[code] = Held(decision, time, time)
        return decision
    }
}
