package com.example.mediathektv

import android.view.KeyEvent.*
import org.junit.Assert.*
import org.junit.Test

class RemoteInputTest {
    private val input = RemoteInput()
    private fun down(code: Int, repeats: Int = 0, time: Long = 0, live: Boolean = false,
                     controls: Boolean = false, countdown: Boolean = false) =
        input.handle(code, ACTION_DOWN, repeats, time, live, controls, countdown)
    private fun up(code: Int) = input.handle(code, ACTION_UP, 0, 0, false, false, false)

    @Test fun systemKeysAndTheirRepeatsAreNeverSwallowed() {
        for (code in listOf(KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN, KEYCODE_BACK, KEYCODE_HOME)) {
            assertEquals(RemoteCommand.DELEGATE, down(code).command)
            assertEquals(RemoteCommand.DELEGATE, down(code, repeats = 5).command)
            assertEquals(RemoteCommand.DELEGATE, up(code).command)
        }
    }

    @Test fun heldOkDoesNotToggleAgainAfterControlsAppear() {
        assertEquals(RemoteCommand.TOGGLE_PLAY, down(KEYCODE_DPAD_CENTER).command)
        assertEquals(RemoteCommand.CONSUME, down(KEYCODE_DPAD_CENTER, repeats = 1, controls = true).command)
        assertEquals(RemoteCommand.CONSUME, up(KEYCODE_DPAD_CENTER).command)
        assertEquals(RemoteCommand.DELEGATE, down(KEYCODE_DPAD_CENTER, controls = true).command)
    }

    @Test fun controlsReceiveRepeatedNavigationAndRelease() {
        for (code in listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)) {
            assertEquals(RemoteCommand.DELEGATE, down(code, controls = true).command)
            assertEquals(RemoteCommand.DELEGATE, down(code, repeats = 3, controls = true).command)
            assertEquals(RemoteCommand.DELEGATE, up(code).command)
        }
    }

    @Test fun heldSeekStaysASeekAndIsThrottledThenAccelerated() {
        assertEquals(10_000L, down(KEYCODE_DPAD_RIGHT).seekMs)
        assertEquals(RemoteCommand.CONSUME, down(KEYCODE_DPAD_RIGHT, 1, 100, controls = true).command)
        assertEquals(10_000L, down(KEYCODE_DPAD_RIGHT, 2, 300, controls = true).seekMs)
        assertEquals(30_000L, down(KEYCODE_DPAD_RIGHT, 10, 2100, controls = true).seekMs)
        assertEquals(RemoteCommand.CONSUME, up(KEYCODE_DPAD_RIGHT).command)
        assertEquals(RemoteCommand.DELEGATE, down(KEYCODE_DPAD_RIGHT, controls = true).command)
    }

    @Test fun liveControlsAreAccessibleWithoutAMenuKey() {
        assertEquals(RemoteCommand.CONTROLS, down(KEYCODE_DPAD_RIGHT, live = true).command)
        up(KEYCODE_DPAD_RIGHT)
        assertEquals(RemoteCommand.DELEGATE, down(KEYCODE_DPAD_RIGHT, live = true, controls = true).command)
    }

    @Test fun heldChannelKeyDoesNotZapThroughAllStations() {
        assertEquals(RemoteCommand.NEXT_CHANNEL, down(KEYCODE_DPAD_DOWN, live = true).command)
        assertEquals(RemoteCommand.CONSUME, down(KEYCODE_DPAD_DOWN, 1, 500, live = true).command)
        up(KEYCODE_DPAD_DOWN)
        assertEquals(RemoteCommand.NEXT_CHANNEL, down(KEYCODE_DPAD_DOWN, live = true).command)
    }

    @Test fun dedicatedPlayAndPauseAreIdempotentCommands() {
        assertEquals(RemoteCommand.PLAY, down(KEYCODE_MEDIA_PLAY, controls = true).command)
        assertEquals(RemoteCommand.PAUSE, down(KEYCODE_MEDIA_PAUSE, controls = true).command)
    }

    @Test fun countdownConfirmationDoesNotBecomePauseOnRepeat() {
        assertEquals(RemoteCommand.NEXT_EPISODE, down(KEYCODE_DPAD_CENTER, countdown = true).command)
        assertEquals(RemoteCommand.CONSUME, down(KEYCODE_DPAD_CENTER, 1, 100).command)
    }

    @Test fun resetAfterFocusLossReleasesOldGestures() {
        down(KEYCODE_DPAD_LEFT)
        input.reset()
        assertEquals(RemoteCommand.DELEGATE, up(KEYCODE_DPAD_LEFT).command)
        assertEquals(RemoteCommand.DELEGATE, down(KEYCODE_DPAD_LEFT, 1, controls = true).command)
    }
}
