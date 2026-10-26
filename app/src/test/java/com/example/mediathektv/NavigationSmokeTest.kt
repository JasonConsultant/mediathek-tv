package com.example.mediathektv

import android.content.Context
import android.view.KeyEvent.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Drückt die Fernbedienungstasten auf der Hauptansicht durch (Robolectric, ohne Gerät):
 * Links ins Menü, Hoch/Runter durch die Rubriken und Reihen, OK im Menü – nichts davon darf abstürzen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationSmokeTest {
    @get:Rule val rule = createComposeRule()

    private fun video(i: Int, channel: String = "ARD") = Video(
        id = "v$i", channel = channel, topic = "Thema $i", title = "Film $i", description = "Beschreibung $i",
        duration = 5000, timestamp = nowSec() - i * 3600L, source = Source("https://x/$i.mp4", "", "DE"),
        language = "", year = 2000 + i, genre = if (i % 2 == 0) "Komödie" else "Drama", episode = null,
        variants = emptyList(), alternatives = emptyList(), geoKnown = true, firstSeen = nowSec() - 60, image = null,
    )

    private fun series(i: Int) = SeriesInfo(
        id = "s$i", name = "Serie $i", channel = "ZDF", kind = SeriesKind.SERIES, crime = i % 3 == 0, count = 5, latest = nowSec() - 60,
        description = "Serie", availability = mapOf("DE" to 5, "AT" to 5, "CH" to 5), newestEpisode = nowSec() - 60, newEpisodes = 1, image = null,
    )

    private fun index() = CatalogIndex(
        generated = nowSec(),
        films = (1..30).map { video(it) }, crime = (31..45).map { video(it, "ZDF") }, docs = (46..60).map { video(it, "ARTE") },
        series = (1..12).map { series(it) }, filmSeries = emptyList(), docSeries = emptyList(),
        live = listOf(
            LiveChannel("Das Erste", "ARD", "https://x/daserste/master.m3u8"),
            LiveChannel("ZDF", "ZDF", "https://x/zdf/master.m3u8"),
            LiveChannel("SRF 1", "SRF", "https://x/srf1.m3u8", srgUrn = "urn:srf:video:1"),
        ),
    )

    private fun press(code: Int) {
        rule.onRoot().performKeyPress(KeyEvent(NativeKeyEvent(ACTION_DOWN, code)))
        rule.onRoot().performKeyPress(KeyEvent(NativeKeyEvent(ACTION_UP, code)))
        rule.waitForIdle()
        // kurze Ruhe wie beim echten Drücken – danach darf das Menü die Seite wechseln
        ShadowLooper.idleMainLooper(MENU_SWITCH_DELAY + 50, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
    }

    @Test fun remoteKeysDoNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Library.init(ctx)
        val app = AppState(ctx)
        app.index = index()
        app.loading = false
        app.detected = "DE"
        val nav = object : Nav {
            override fun open(tile: Tile) {}
            override fun browse(spec: BrowseSpec) {}
            override fun tab(tab: Tab) { app.tab = tab }
        }
        rule.setContent { MaterialTheme { SideMenu(app) { MainContent(app, nav, {}, {}, {}) } } }
        rule.waitForIdle()
        assertEquals(Tab.START, app.tab)

        val start = app.pages.getValue(Tab.START)
        val first = start.focusKey
        assertNotNull("erste Kachel hat den Fokus", first)

        // Startseite: durch die Reihen und Kacheln – die Tasten müssen wirklich ankommen
        press(KEYCODE_DPAD_RIGHT)
        assertNotEquals("Rechts wechselt die Kachel", first, start.focusKey)
        repeat(3) { press(KEYCODE_DPAD_DOWN) }
        assertNotEquals("Runter wechselt die Reihe", first!!.substringBefore('|'), start.focusKey!!.substringBefore('|'))
        repeat(3) { press(KEYCODE_DPAD_RIGHT) }
        repeat(2) { press(KEYCODE_DPAD_UP) }
        // Links ins Menü, dort durch alle Rubriken (jede baut ihre Seite auf)
        repeat(6) { press(KEYCODE_DPAD_LEFT) }
        assertTrue("Menü hat den Fokus", app.menuFocused)
        assertTrue("Beschriftungen sichtbar, wenn das Menü offen ist (Geräte-Test erkennt das Menü daran)",
            rule.onAllNodesWithText("Einstellungen").fetchSemanticsNodes().isNotEmpty())
        assertEquals("Links landet auf der aktuellen Rubrik, nicht auf dem Punkt auf gleicher Höhe", Tab.START, app.tab)
        press(KEYCODE_DPAD_DOWN)
        assertNotEquals("Runter im Menü wechselt die Rubrik", Tab.START, app.tab)
        assertTrue("Menü bleibt offen (Seite holt den Fokus nicht zurück)", app.menuFocused)
        repeat(Tab.entries.size) { press(KEYCODE_DPAD_DOWN) }
        assertEquals(Tab.SETTINGS, app.tab)
        // Vier Schritte hoch: Krimis – die Seite jeder Rubrik dazwischen wurde aufgebaut, ohne zu stören
        repeat(4) { press(KEYCODE_DPAD_UP) }
        assertEquals(Tab.KRIMIS, app.tab)
        repeat(4) { press(KEYCODE_DPAD_DOWN) }
        repeat(Tab.entries.size) { press(KEYCODE_DPAD_UP) }
        assertEquals(Tab.START, app.tab)
        // Menü: Runter auf TV-Sender, OK öffnet die Seite; dort blättern und zurück ins Menü
        press(KEYCODE_DPAD_DOWN)
        assertEquals(Tab.LIVE, app.tab)
        press(KEYCODE_DPAD_CENTER)
        assertTrue("OK im Menü: Fokus auf der Seite", app.pageFocused && !app.menuFocused)
        assertTrue("Beschriftungen weg, wenn das Menü zu ist",
            rule.onAllNodesWithText("Einstellungen").fetchSemanticsNodes().isEmpty())
        assertEquals("ch|Das Erste", app.pages.getValue(Tab.LIVE).focusKey)
        repeat(3) { press(KEYCODE_DPAD_DOWN) }
        assertEquals("ch|ZDF", app.pages.getValue(Tab.LIVE).focusKey)   // SRF 1 gehört nicht zum deutschen Angebot
        repeat(6) { press(KEYCODE_DPAD_LEFT) }
        repeat(2) { press(KEYCODE_DPAD_DOWN) }
        press(KEYCODE_DPAD_CENTER)
        repeat(3) { press(KEYCODE_DPAD_RIGHT) }
        repeat(3) { press(KEYCODE_DPAD_DOWN) }
        repeat(8) { press(KEYCODE_DPAD_LEFT) }
        repeat(3) { press(KEYCODE_DPAD_UP) }
        press(KEYCODE_DPAD_CENTER)
        rule.waitForIdle()
    }
}
