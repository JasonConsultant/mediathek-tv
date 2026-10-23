package com.example.mediathektv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTest {
    @Test fun language() {
        assertEquals("Deutsch", Meta.language("Peter von Kant"))
        assertEquals("Originalton", Meta.language("Sanfte Monster (Originalversion mit Untertitel)"))
        assertEquals("Englisch", Meta.language("The Pope's Exorcist (Englisch)"))
        assertEquals("Deutsch (Hörfassung)", Meta.language("Fischer sucht Frau (Audiodeskription)"))
        assertEquals("Deutsch (Hörfassung)", Meta.language("Hörfassung: Haarscharf ins Herz"))
        assertEquals("Haarscharf ins Herz", Meta.baseTitle("Hörfassung: Haarscharf ins Herz"))
    }

    @Test fun titles() {
        assertEquals("Sanfte Monster", Meta.baseTitle("Sanfte Monster (Originalversion mit Untertitel)"))
        assertEquals("Folge 975: Unverstanden", Meta.displayTitle("Folge 975: Unverstanden (S25/E12) (Audiodeskription)"))
        assertEquals("Wer ohne Schuld ist", Meta.displayTitle("Wer ohne Schuld ist - Fernsehfilm, Deutschland 2023"))
    }

    @Test fun episodes() {
        assertEquals(Episode(25, 12), Meta.episode("Folge 975: Unverstanden (S25/E12)"))
        assertEquals(Episode(11, 1), Meta.episode("Mord im Mittsommer - Staffel 11 (1/3) - Fall 23: Linnea"))
        assertEquals(Episode(9, 4), Meta.episode("Die Abrechnung (4/10)", "Soko Donau Staffel 9"))
        assertEquals(Episode(null, 3612), Meta.episode("Folge 3612"))
        assertNull(Meta.episode("Liebe nach Rezept"))
    }

    @Test fun seriesNames() {
        assertEquals("Soko Donau", Meta.cleanSeriesName("Soko Donau Staffel 9"))
        assertEquals("Mord im Mittsommer", Meta.cleanSeriesName("Mord im Mittsommer - Staffel 11"))
        assertEquals("Haus aus Glas", Meta.seriesNameFromTitle("Haus aus Glas (2/6) - Die Nachfolge"))
        assertEquals("Fall 23: Linnea", Meta.episodeTitle("Mord im Mittsommer - Staffel 11 - Fall 23: Linnea", "Mord im Mittsommer"))
    }

    @Test fun stripTopic() {
        assertEquals("Doppelmord (28)", Meta.stripTopic("Doppelmord (28) – Der Usedom-Krimi", "Der Usedom-Krimi"))
        assertEquals("Das Wunder von Wustermark", Meta.stripTopic("Polizeiruf 110: Das Wunder von Wustermark", "Polizeiruf 110"))
        assertEquals("Tatort", Meta.stripTopic("Tatort", "Tatort"))
    }

    @Test fun yearAndGenre() {
        val d = "\"Sanfte Monster\" ist ein Drama von Laszlo Csuja und Anna Nemes aus dem Jahr 2021."
        assertEquals(2021, Meta.year("Sanfte Monster", d))
        assertEquals("Drama", Meta.genre("Sanfte Monster", d))
        assertEquals(2022, Meta.year("Peter von Kant", "… über Liebe und Eifersucht. Ein Comedy-Drama von 2022."))
        assertEquals("Komödie", Meta.genre("Peter von Kant", "Ein Comedy-Drama von 2022."))
        assertEquals(1990, Meta.year("Nikita - Spielfilm, Frankreich/Italien 1990", ""))
        assertNull(Meta.year("Liebe nach Rezept", "Michael soll wieder Romantiker werden."))
        assertNull(Meta.genre("Liebe nach Rezept", "Michael soll wieder Romantiker werden."))
    }
}
