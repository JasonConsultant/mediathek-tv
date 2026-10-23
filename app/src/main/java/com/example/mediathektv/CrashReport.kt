package com.example.mediathektv

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Schreibt jeden Absturz in eine Datei; beim nächsten Start zeigt die App die Fehlerstelle an.
 * So lässt sich ein Fehler vom Fernseher abfotografieren, ohne Kabel und ohne Entwicklerwerkzeuge.
 */
class MediathekApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReport.write(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashReport {
    private const val FILE = "crash.txt"

    fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(context.filesDir, FILE).writeText(
            "Mediathek TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
            "Thread: ${thread.name}\n\n" + compact(trace)
        )
    }

    /** Gespeicherten Bericht lesen und löschen – null, wenn es keinen gibt. */
    fun take(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    /** Nur die aussagekräftigen Zeilen: Fehler, Ursache und Stellen in unserem Code bzw. die ersten Rahmen. */
    internal fun compact(trace: String, maxLines: Int = 18): String {
        val lines = trace.lines()
        val keep = ArrayList<String>()
        var frames = 0
        for (line in lines) {
            val t = line.trim()
            when {
                t.isEmpty() -> continue
                !t.startsWith("at ") -> { keep.add(t); frames = 0 }           // Fehlermeldung / "Caused by"
                t.contains("com.example.mediathektv") || frames < 4 -> { keep.add(t.removePrefix("at ")); frames++ }
            }
            if (keep.size >= maxLines) break
        }
        return keep.joinToString("\n")
    }
}
