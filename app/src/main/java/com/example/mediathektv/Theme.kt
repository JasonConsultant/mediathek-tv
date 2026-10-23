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

    /** Farbverlauf je Sender als Kachel-Hintergrund (keine Logos). */
    fun channel(ch: String): Color = when {
        ch == "ARD" || ch == "ONE" || ch == "Das Erste" -> Color(0xFF0D3B8C)
        ch.startsWith("ZDF") -> Color(0xFFE8650B)
        ch.startsWith("ARTE") -> Color(0xFFB0271E)
        ch == "3Sat" -> Color(0xFF8E2A6B)
        ch == "PHOENIX" -> Color(0xFF1E6F5C)
        ch == "SRF" -> Color(0xFF8C0A0A)
        ch == "ORF" -> Color(0xFFA3122A)
        else -> Color(0xFF2B5CA8)  // Dritte Programme
    }
}
