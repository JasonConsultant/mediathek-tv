package com.example.mediathektv

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Läuft auf einem echten Android-TV-Emulator (GitHub Actions, Android 12 TV – wie die MagentaTV One).
 * Bedient die App ausschließlich mit Fernbedienungstasten, wie ein Mensch: Menü, Reihen, Details,
 * Film abspielen, Live-TV, Senderwechsel. Ein Absturz der App beendet den Test sofort mit Fehler.
 *
 * Der Emulator steht in einem Rechenzentrum in den USA – sichtbar sind dort nur Sendungen ohne Ländersperre.
 */
@RunWith(AndroidJUnit4::class)
class TvDeviceTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val steps = ArrayList<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    private var testName = "test"

    /** Bei einem Fehler: Bildschirmfoto, Bildschirminhalt und die letzten Schritte – noch vor dem Schließen der App. */
    private fun diagnose(reason: String) {
        runCatching { device.executeShellCommand("screencap -p /data/local/tmp/fehler-$testName.png") }
        runCatching {
            val out = java.io.ByteArrayOutputStream(); device.dumpWindowHierarchy(out)
            val texts = Regex("text=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.toList()
            val focused = Regex("<node[^>]*focused=\"true\"[^>]*>").findAll(out.toString()).map { it.value.take(300) }.toList()
            println("DIAGNOSE $testName: $reason\nSichtbare Texte: $texts\nFokus: $focused")
        }
        println("Schritte ($testName):\n" + steps.takeLast(40).joinToString("\n"))
    }

    @get:Rule val watcher = object : TestWatcher() {
        override fun starting(description: Description) { testName = description.methodName }
    }

    private fun check(message: String, condition: Boolean) { if (!condition) { diagnose(message); fail(message) } }

    @Before fun start() {
        Playback.state = "idle"
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForCatalog()
    }

    @After fun stop() { scenario?.close() }

    // ------------------------------------------------------------------ Tests

    /** Kreuz und quer durch Menü, Rubriken und Reihen – darf nie abstürzen oder hängen bleiben. */
    @Test fun navigationWithRemote() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 3); press(KeyEvent.KEYCODE_DPAD_RIGHT, 4); press(KeyEvent.KEYCODE_DPAD_UP, 2)
        openMenu(expectTab = Tab.START)
        // durch alle Rubriken und zurück, jede Seite wird dabei aufgebaut
        press(KeyEvent.KEYCODE_DPAD_DOWN, Tab.entries.size, pauseMs = 500)
        press(KeyEvent.KEYCODE_DPAD_UP, Tab.entries.size, pauseMs = 500)
        // schnell durchblättern (gedrückt halten)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 8, pauseMs = 60)
        press(KeyEvent.KEYCODE_DPAD_UP, 8, pauseMs = 60)
        // in jede Hauptrubrik hinein, dort blättern, zurück ins Menü
        for (tab in listOf(Tab.SERIEN, Tab.FILME, Tab.DOKUS, Tab.LIVE, Tab.START)) {
            goToTab(tab)
            press(KeyEvent.KEYCODE_DPAD_DOWN, 3); press(KeyEvent.KEYCODE_DPAD_RIGHT, 3); press(KeyEvent.KEYCODE_DPAD_UP, 1)
            openMenu(expectTab = tab)
        }
        assertAppInFront()
    }

    /** Film öffnen, abspielen (Wiedergabe muss wirklich laufen), Player-Tasten, zurück. */
    @Test fun filmPlays() {
        goToTab(Tab.FILME)
        var played = false
        for (attempt in 1..5) {
            press(KeyEvent.KEYCODE_ENTER)                                   // Details öffnen
            check("Details öffnen sich (Versuch $attempt)",
                device.wait(Until.hasObject(By.textContains("Abspielen").pkg(pkg)), 15_000) ||
                device.hasObject(By.textContains("Fortsetzen")) || device.hasObject(By.textContains("Trotzdem versuchen")))
            Thread.sleep(1500)                                              // Verfügbarkeitsprüfung
            if (device.hasObject(By.textContains("nicht mehr verfügbar"))) {
                step("Film $attempt beim Sender gelöscht – nächster")
                press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_DPAD_RIGHT); continue
            }
            Playback.state = "idle"
            press(KeyEvent.KEYCODE_ENTER)                                   // Abspielen
            val result = waitForPlayback(60_000)
            step("Film $attempt: $result")
            if (result == "playing") { played = true; break }
            // Fehler: Player schließt sich selbst, wir sind wieder in den Details
            device.wait(Until.gone(By.res(pkg, "exo_content_frame")), 5_000)
            press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        check("Mindestens ein Film läuft im Player", played)

        Thread.sleep(3000)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)                                  // 10 s vor
        press(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1000)             // Pause
        check("OK pausiert: erwartet " + "paused" + ", war " + (Playback.state), "paused" == (Playback.state))
        press(KeyEvent.KEYCODE_DPAD_CENTER)                                 // weiter
        val resumed = waitForPlayback(20_000)
        check("OK setzt fort: war $resumed", resumed == "playing")
        press(KeyEvent.KEYCODE_BACK)                                        // Bedienleiste zu
        press(KeyEvent.KEYCODE_DPAD_UP)                                     // Untertitel-Auswahl (oder Hinweis)
        Thread.sleep(800)
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 6, pauseMs = 60)                 // Spulen gedrückt halten
        press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_BACK)          // Leiste zu, Player verlassen
        check("Zurück aus dem Player in die Details",
            device.wait(Until.hasObject(By.textContains("Fortsetzen").pkg(pkg)), 10_000) ||
            device.hasObject(By.textContains("Abspielen")))
        press(KeyEvent.KEYCODE_BACK)
        assertAppInFront()
    }

    /** TV-Sender: ein Sender läuft, Hoch/Runter wechselt den Sender, ohne Absturz. */
    @Test fun liveTvPlaysAndZaps() {
        goToTab(Tab.LIVE)
        press(KeyEvent.KEYCODE_ENTER)
        var result = waitForPlayback(45_000)
        step("Sender 1: $result")
        var zaps = 0
        while (result != "playing" && zaps < 6) {                           // nicht jeder Sender läuft überall
            Playback.state = "idle"
            press(KeyEvent.KEYCODE_DPAD_DOWN); zaps++
            result = waitForPlayback(30_000)
            step("Sender ${zaps + 1}: $result")
        }
        check("Ein Livesender läuft: erwartet " + "playing" + ", war " + (result), "playing" == (result))
        // Zappen: hoch, runter, Programm +/−, OK (Pause), Info
        press(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(2000)
        press(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(2000)
        press(KeyEvent.KEYCODE_CHANNEL_UP); Thread.sleep(1500)
        Playback.state = "idle"
        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        val afterZap = waitForPlayback(45_000)
        check("Nach dem Zappen läuft wieder ein Sender: war $afterZap", afterZap == "playing")
        press(KeyEvent.KEYCODE_INFO); Thread.sleep(800)
        press(KeyEvent.KEYCODE_DPAD_LEFT); Thread.sleep(800)               // Bedienleiste
        press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_BACK)
        assertAppInFront()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun step(s: String) { steps.add(s); println("· $s") }

    private fun press(code: Int, times: Int = 1, pauseMs: Long = 450) = repeat(times) {
        step(KeyEvent.keyCodeToString(code))
        device.pressKeyCode(code)
        Thread.sleep(pauseMs)
    }

    private fun waitForCatalog() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(By.textContains("konnte nicht geladen werden"))) check("Katalog konnte nicht geladen werden", false)
            if (device.hasObject(By.pkg(pkg)) && !device.hasObject(By.textContains("Katalog wird geladen"))) {
                Thread.sleep(3000)                                          // Seite aufbauen lassen
                device.findObject(By.text("Schließen"))?.click()             // evtl. Absturzbericht eines früheren Laufs
                step("Katalog geladen")
                check("Nach dem Start hat die Seite den Fokus, nicht das Menü (war ${focusedTab()})", focusedTab() == null)
                return
            }
            Thread.sleep(500)
        }
        check("Katalog nach 2 Minuten nicht geladen", false)
    }

    /** Kennung des Menüpunkts mit Fokus ("tab-FILME"), sonst null. */
    private fun focusedTabObject() = Tab.entries.firstNotNullOfOrNull { t ->
        device.findObject(By.res("tab-${t.name}"))?.takeIf { it.isFocused }
    }

    private fun focusedTab(): String? = repeat3 { focusedTabObject()?.resourceName?.substringAfterLast('/') }

    private fun focusedWidth(): Int = repeat3 { focusedTabObject()?.visibleBounds?.width() } ?: 0

    /** Bildschirm ändert sich gerade (veralteter Knoten) – kurz warten und nochmal lesen. */
    private fun <T> repeat3(read: () -> T?): T? {
        repeat(3) { try { return read() } catch (_: androidx.test.uiautomator.StaleObjectException) { Thread.sleep(200) } }
        return null
    }

    /** Taste drücken und warten, bis sich der Fokus im Menü wirklich bewegt hat. */
    private fun pressInMenu(code: Int) {
        val before = focusedTab()
        press(code, pauseMs = 0)
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && focusedTab() == before) Thread.sleep(100)
        Thread.sleep(150)
    }

    /** Links ins Menü: Fokus muss auf einem Menüpunkt landen, und das Menü muss aufgeklappt sein (breit). */
    private fun openMenu(expectTab: Tab? = null) {
        repeat(8) {
            if (focusedTab() != null) {
                Thread.sleep(400)                                           // Aufklappen abwarten
                val width = focusedWidth()
                step("Menü offen auf ${focusedTab()}, Breite $width px")
                check("Menü ist aufgeklappt (Beschriftungen sichtbar)", width > 300)
                expectTab?.let { check("Links landet auf der aktuellen Rubrik ${it.name}, war ${focusedTab()}", focusedTab() == "tab-${it.name}") }
                return
            }
            press(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        check("Menü öffnet sich mit Links", focusedTab() != null)
    }

    /** Über das Menü in eine Rubrik und mit OK auf die Seite. */
    private fun goToTab(tab: Tab) {
        if (device.hasObject(By.res(pkg, "exo_content_frame"))) press(KeyEvent.KEYCODE_BACK)
        openMenu()
        repeat(Tab.entries.size + 2) {
            val now = focusedTab()?.removePrefix("tab-")?.let { Tab.valueOf(it) } ?: return@repeat
            if (now == tab) return@repeat
            pressInMenu(if (now.ordinal < tab.ordinal) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        check("Menüpunkt ${tab.name} erreicht, war ${focusedTab()}", focusedTab() == "tab-${tab.name}")
        Thread.sleep(500)
        press(KeyEvent.KEYCODE_ENTER)                                       // Seite öffnen, Fokus auf die Seite
        Thread.sleep(1200)
        check("Nach OK hat die Seite den Fokus (${tab.label}), war ${focusedTab()}", focusedTab() == null)
    }

    /** Wartet, bis der Player läuft oder einen Fehler meldet. */
    private fun waitForPlayback(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = Playback.state
            if (s == "playing" || s.startsWith("error") || s == "closed") return s
            Thread.sleep(250)
        }
        return "timeout(${Playback.state})"
    }

    private fun assertAppInFront() =
        check("App ist noch im Vordergrund (kein Absturz): erwartet " + pkg + ", war " + (device.currentPackageName), pkg == (device.currentPackageName))
}
