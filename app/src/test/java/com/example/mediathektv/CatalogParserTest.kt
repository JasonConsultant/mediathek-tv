package com.example.mediathektv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class CatalogParserTest {
    private val json = """
        {"version":1,"generated":1792900000,
         "films":[{"i":"a1","c":"ARD","t":"Filme in der ARD","n":"Liebe nach Rezept","d":"Text","s":5280,"ts":1792800000,
                   "u":"https://x/de.mp4","ut":"https://x/ut.xml","g":"DE","y":2007,
                   "v":[{"l":"Deutsch (Hörfassung)","u":"https://x/ad.mp4","g":"DE"}],
                   "a":[{"u":"https://x/ch.mp4","g":"DE-AT-CH"}]}],
         "crime":[],"docs":[{"i":"d1","c":"3Sat","t":"Dokumentarfilm","n":"Doku","s":3000,"ts":1,"u":"https://x/d.mp4"}],
         "series":[{"id":"s1","n":"Die Rosenheim-Cops","c":"ZDF","k":"serie","cnt":3,"new":5,"d":"x","av":{"DE":3,"AT":3,"CH":0}}],
         "filmSeries":[{"id":"f1","n":"Tatort","c":"ARD","k":"filmreihe","cr":1,"cnt":2,"new":5,"av":{"DE":2,"AT":2,"CH":2}}],
         "docSeries":[]}
    """.trimIndent()

    private fun gzip(s: String) = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(s.toByteArray()) } }.toByteArray()

    @Test fun parsesIndexPlainAndGzip() {
        for (bytes in listOf(json.toByteArray(), gzip(json))) {
            val idx = CatalogParser.parseIndex(bytes.inputStream())
            assertEquals(1792900000L, idx.generated)
            assertEquals(1, idx.films.size)
            val f = idx.films[0]
            assertEquals("Liebe nach Rezept", f.title)
            assertEquals(2007, f.year)
            assertTrue(f.hasSubtitles)
            assertEquals(1, f.variants.size)
            assertEquals("Deutsch (Hörfassung)", f.variants[0].language)
            assertEquals(SeriesKind.FILM_SERIES, idx.filmSeries[0].kind)
            assertTrue(idx.filmSeries[0].crime)
            assertEquals(0, idx.series[0].countIn("CH"))
        }
    }

    @Test fun geoSelectsPlayableSource() {
        val f = CatalogParser.parseIndex(json.byteInputStream()).films[0]
        // Hauptquelle nur DE -> in der Schweiz wird die Alternative genommen
        assertEquals("https://x/de.mp4", f.forCountry("DE")!!.url)
        assertEquals("https://x/ch.mp4", f.forCountry("CH")!!.url)
        // Hörfassung ist nur in DE freigegeben
        assertEquals(1, f.forCountry("DE")!!.variants.size)
        assertEquals(0, f.forCountry("CH")!!.variants.size)
        assertNull(f.copy(alternatives = emptyList()).forCountry("CH"))
    }

    @Test fun geoRules() {
        assertTrue(Geo.playable("", "CH"))
        assertTrue(Geo.playable("DE-AT-CH", "CH"))
        assertFalse(Geo.playable("DE", "CH"))
        assertTrue(Geo.playable("DE-AT-CH-EU", "AT"))
        assertFalse(Geo.playable("DE-EU", "CH"))   // Schweiz ist nicht in der EU
    }
}
