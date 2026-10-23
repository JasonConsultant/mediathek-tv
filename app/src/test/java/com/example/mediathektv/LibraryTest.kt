package com.example.mediathektv

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    private val video = Video(
        id = "a1", channel = "ZDF", topic = "Die Rosenheim-Cops", title = "Der letzte Ton", description = "Text",
        duration = 2700, timestamp = 1792800000, source = Source("https://x/v.mp4", "https://x/ut.xml", "DE-AT-CH"),
        language = "Deutsch", year = 2024, genre = "Krimi", episode = Episode(24, 7), firstSeen = 1792850000,
        variants = listOf(
            Video("a1-0", "ZDF", "Die Rosenheim-Cops", "Der letzte Ton", "Text", 2700, 1792800000,
                Source("https://x/ad.mp4"), language = "Deutsch (Hörfassung)")
        ),
    )

    @Test fun videoSurvivesSaveAndLoad() {
        val back = CatalogParser.parseVideo(Library.videoJson(video).toString())
        assertEquals(video.stableKey, back.stableKey)
        assertEquals(video.url, back.url)
        assertEquals(video.subtitleUrl, back.subtitleUrl)
        assertEquals("DE-AT-CH", back.source.geo)
        assertEquals(Episode(24, 7), back.episode)
        assertEquals(2024, back.year)
        assertEquals(1792850000L, back.firstSeen)
        assertEquals("Deutsch (Hörfassung)", back.variants.single().language)
    }

    @Test fun stableKeyIgnoresUrlAndId() {
        val reuploaded = video.copy(id = "other", source = Source("https://y/new.mp4"))
        assertEquals(video.stableKey, reuploaded.stableKey)
    }
}

class CopyLimitedTest {
    @org.junit.Test fun copiesWithinLimit() {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(ByteArray(1000).inputStream(), out, 1000)
        org.junit.Assert.assertEquals(1000, out.size())
    }
    @org.junit.Test(expected = IllegalStateException::class) fun rejectsOversize() {
        copyLimited(ByteArray(1001).inputStream(), java.io.ByteArrayOutputStream(), 1000)
    }
}
