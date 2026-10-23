package com.example.mediathektv

import androidx.compose.ui.graphics.Color

/** Dunkles Blau-Design im Stil moderner TV-Oberflächen. */
object TvColors {
    val Background = Color(0xFF0B1324)
    val Surface = Color(0xFF16213A)
    val SurfaceHigh = Color(0xFF1F2D4F)
    val Accent = Color(0xFF2E7CF6)
    val Text = Color(0xFFF2F5FA)
    val TextMuted = Color(0xFF9AA7C2)
}

/** Reihen auf der Startseite – Reihenfolge = Anzeige. */
val HOME_ROWS = listOf(
    "Neu bei ARD" to listOf("ARD", "Das Erste"),
    "Neu im ZDF" to listOf("ZDF"),
    "Dritte Programme" to listOf("BR", "SWR", "NDR", "WDR", "MDR", "HR", "RBB", "SR"),
    "ARTE & 3sat" to listOf("ARTE.DE", "3Sat"),
    "phoenix & Doku" to listOf("PHOENIX", "ZDFinfo"),
    "Schweiz (SRF)" to listOf("SRF")
)
