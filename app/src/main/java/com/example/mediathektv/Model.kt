package com.example.mediathektv

import java.util.Calendar

/** Eine abspielbare Quelle mit Geo-Freigabe ("" = weltweit, sonst z.B. "DE-AT-CH" oder "DE"). */
data class Source(val url: String, val subtitleUrl: String = "", val geo: String = "") {
    fun playableIn(country: String): Boolean = Geo.playable(geo, country)
}

/** Eine Sendung (Film, Folge, Doku). [title] ist bereinigt – ohne Sprach- und Folgenzusatz. */
data class Video(
    val id: String,
    val channel: String,
    val topic: String,
    val title: String,
    val description: String,
    val duration: Int,                          // Sekunden
    val timestamp: Long,                        // Sendedatum, Unix-Sekunden
    val source: Source,
    val language: String = "Deutsch",
    val year: Int? = null,
    val genre: String? = null,
    val episode: Episode? = null,
    val variants: List<Video> = emptyList(),    // weitere Sprach-/Hörfassungen
    val alternatives: List<Source> = emptyList(), // gleiche Sendung mit anderer Geo-Freigabe
    val geoKnown: Boolean = true,               // false bei Live-Suchtreffern (ohne Geo-Angabe)
    val firstSeen: Long = 0,                    // erstmals im Katalog (Unix-Sekunden), für "Neu"
) {
    val url: String get() = source.url
    val subtitleUrl: String get() = source.subtitleUrl
    val minutes: Int get() = duration / 60
    val hasSubtitles: Boolean get() = subtitleUrl.isNotBlank()
    /** Alle Fassungen inklusive dieser, Deutsch zuerst. */
    val versions: List<Video> get() = listOf(this) + variants

    fun availableIn(country: String): Boolean =
        source.playableIn(country) || alternatives.any { it.playableIn(country) }

    /** Diese Sendung mit einer im Land abspielbaren Quelle, sonst null. Fassungen ebenso gefiltert. */
    fun forCountry(country: String): Video? {
        val src = (listOf(source) + alternatives).firstOrNull { it.playableIn(country) } ?: return null
        return copy(source = src, variants = variants.mapNotNull { it.forCountry(country) }, alternatives = emptyList())
    }
}

data class Episode(val season: Int?, val number: Int) {
    val label: String get() = if (season != null) "S$season · E$number" else "Folge $number"
    val sortKey: Int get() = (season ?: 0) * 10_000 + number
}

enum class SeriesKind { SERIES, FILM_SERIES, DOC_SERIES }

/** Kopfdaten einer Serie/Reihe; die Folgen werden erst beim Öffnen geladen. */
data class SeriesInfo(
    val id: String,
    val name: String,
    val channel: String,
    val kind: SeriesKind,
    val crime: Boolean,
    val count: Int,
    val latest: Long,
    val description: String,
    val availability: Map<String, Int>,         // abspielbare Folgen je Land
    val newestEpisode: Long = 0,                // Erstauftritt der neuesten Folge
    val newEpisodes: Int = 0,                   // Folgen der letzten 24 Stunden
) {
    fun availableIn(country: String) = (availability[country] ?: 0) > 0
    fun countIn(country: String) = availability[country] ?: count
}

/** Geo-Freigaben der Sender. */
object Geo {
    val countries = listOf("CH", "DE", "AT")
    private val eu = setOf("DE", "AT", "FR", "IT", "NL", "BE", "LU", "ES", "PT", "DK", "SE", "FI", "PL", "CZ", "IE")

    fun playable(geo: String, country: String): Boolean {
        if (geo.isBlank()) return true
        val parts = geo.split('-')
        return country in parts || ("EU" in parts && country in eu)
    }

    fun name(country: String) = when (country) {
        "CH" -> "Schweiz"; "DE" -> "Deutschland"; "AT" -> "Österreich"; else -> country
    }
}

/** Liest Zusatzinfos aus Titel und Beschreibung. Liefert null, wenn nichts Verlässliches drinsteht. */
object Meta {
    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    // Sprach-/Barrierefreiheits-Zusätze am Titelende, z.B. "(Originalversion mit Untertitel)"
    private val versionSuffix = Regex(
        "\\s*\\((?:Originalversion|Original|OmU|Englisch|Französisch|Audiodeskription|" +
            "mit Gebärdensprache|Gebärdensprache|mit Untertitel|hörgeschädigt|Hörfassung|ÖGS)[^)]*\\)" +
            "|^\\s*(?:Hörfassung|ÖGS)\\s*[:|-]\\s*",
        RegexOption.IGNORE_CASE
    )
    private val seasonEpisode = Regex("\\s*\\(S(\\d+)/E(\\d+)\\)")
    private val partOf = Regex("\\s*\\((\\d+)/(\\d+)\\)")
    private val folge = Regex("\\bFolge (\\d+)\\b")
    private val staffel = Regex("\\bStaffel\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val staffelSuffix = Regex("\\s*[-–:,]?\\s*Staffel\\s+\\d+\\s*$", RegexOption.IGNORE_CASE)
    // "Wer ohne Schuld ist - Fernsehfilm, Deutschland 2023" -> "Wer ohne Schuld ist"
    private val filmSuffix = Regex("\\s+-\\s+(?:Spielfilm|Fernsehfilm|Kinofilm|Dokumentarfilm),.*$")

    fun language(rawTitle: String): String = when {
        rawTitle.contains("Audiodeskription", true) || rawTitle.contains("Hörfassung", true) -> "Deutsch (Hörfassung)"
        rawTitle.contains("Gebärden", true) || rawTitle.contains("ÖGS") -> "Gebärdensprache"
        rawTitle.contains("(Original", true) || rawTitle.contains("(OmU", true) -> "Originalton"
        rawTitle.contains("(Englisch", true) -> "Englisch"
        rawTitle.contains("(Französisch", true) -> "Französisch"
        else -> "Deutsch"
    }

    /** Titel ohne Sprachzusatz (für das Zusammenfassen von Fassungen). */
    fun baseTitle(rawTitle: String): String = rawTitle.replace(versionSuffix, "").trim()

    /** Anzeigetitel: zusätzlich ohne "(S1/E2)" bzw. "(2/6)". */
    fun displayTitle(rawTitle: String): String =
        baseTitle(rawTitle).replace(seasonEpisode, "").replace(partOf, "").replace(filmSuffix, "").trim()

    /** Folgennummer aus "(S1/E2)", "(2/6)" oder "Folge 12"; Staffel notfalls aus "Staffel 3". */
    fun episode(rawTitle: String, topic: String = ""): Episode? {
        seasonEpisode.find(rawTitle)?.let { return Episode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        val number = (partOf.find(rawTitle) ?: folge.find(rawTitle))?.groupValues?.get(1)?.toInt() ?: return null
        val season = (staffel.find(rawTitle) ?: staffel.find(topic))?.groupValues?.get(1)?.toInt()
        return Episode(season, number)
    }

    /** Entfernt den Reihennamen, wenn er im Titel wiederholt wird: "Doppelmord – Der Usedom-Krimi" -> "Doppelmord". */
    fun stripTopic(title: String, topic: String): String {
        if (topic.isBlank()) return title
        val t = Regex.escape(topic)
        return title
            .replace(Regex("^$t\\s*[-–:|]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[-–|]\\s*$t$", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { title }
    }

    private val rubrics = setOf(
        "Filme in der ARD", "Filme im Ersten", "FilmMittwoch im Ersten", "Film im NDR", "Debüt im Dritten", "ARD Debüt",
        "Film & Fiktion", "Filme", "Spielfilm-Highlights", "Herzkino", "Das kleine Fernsehspiel", "Spielfilm",
        "Fernsehfilm", "Spielfilm in 3sat", "Fernsehfilm in 3sat", "Der Fernsehfilm der Woche", "3satPublikumspreis",
        "Film", "Schweizer Film", "Krimi und Thriller", "Dokumentationen", "Dokumentarfilm", "Dokumentarfilmzeit",
        "DOK", "Dokus", "ZDFinfo Doku", "Doku & Reportage", "Dokus & Reportagen im BR", "Cinema Strikes Back",
    )
    private val rubricPrefix = Regex("^(Kino|Fernsehfilme und Serien|Entdeckung der Welt|Wissenschaft|Geschichte|Aktuelles und Gesellschaft|Kultur und Pop) - ")

    /** Sammel-Rubrik ("Filme in der ARD") statt eines Reihennamens ("Tatort")? */
    fun isRubric(topic: String): Boolean = topic in rubrics || rubricPrefix.containsMatchIn(topic) || topic.endsWith("- die Einzeldokus")

    /** "Soko Donau Staffel 9" -> "Soko Donau" */
    fun cleanSeriesName(name: String): String = name.replace(staffelSuffix, "").trim()

    /** Folgentitel ohne vorangestellten Seriennamen und Staffelangabe. */
    fun episodeTitle(title: String, seriesName: String): String {
        var t = title
        if (t.startsWith(seriesName, ignoreCase = true)) t = t.substring(seriesName.length)
        t = t.replace(Regex("^\\s*[-–:,]?\\s*Staffel\\s+\\d+", RegexOption.IGNORE_CASE), "")
        return t.trim().trimStart('-', '–', ':', ',').trim().ifBlank { title }
    }

    /** Name einer Serie aus einem Titel wie "Haus aus Glas (2/6) - Die Nachfolge". */
    fun seriesNameFromTitle(rawTitle: String): String? {
        val t = baseTitle(rawTitle)
        val m = partOf.find(t) ?: seasonEpisode.find(t) ?: return null
        return t.substring(0, m.range.first).trim().ifBlank { null }
    }

    private val yearPatterns = listOf(
        Regex("(?:aus dem Jahr|im Jahr|Produktionsjahr|Jahrgang)\\s+((?:19|20)\\d{2})\\b"),
        Regex("\\b(?:Drama|Komödie|Thriller|Film|Spielfilm|Krimi|Western|Dokumentarfilm|Comedy-Drama|Tragikomödie|Liebesfilm)\\b[^.]{0,60}?\\bvon\\s+((?:19|20)\\d{2})\\b"),
        Regex("\\b(?:Deutschland|Österreich|Schweiz|Frankreich|Italien|Spanien|USA|Großbritannien|Kanada|Dänemark|Schweden|Norwegen|Finnland|Belgien|Niederlande|Polen|Tschechien|Irland|Australien|Japan|Südkorea|Island|Ungarn|Israel|Luxemburg|Argentinien|Mexiko)(?:\\s*/\\s*[A-ZÄÖÜ][a-zäöü]+)*\\s*,?\\s+((?:19|20)\\d{2})\\b"),
    )

    fun year(rawTitle: String, description: String): Int? {
        val text = "$rawTitle. $description"
        for (p in yearPatterns) {
            val y = p.find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (y != null && y in 1900..currentYear) return y
        }
        return null
    }

    // Reihenfolge zählt: spezifische Begriffe vor allgemeinen.
    private val genres = listOf(
        "Tragikomödie" to "Tragikomödie",
        "Komödie|Comedy|Komödiantisch" to "Komödie",
        "Psychothriller|Thriller" to "Thriller",
        "Kriminalfilm|Krimi|Tatort|Polizeiruf" to "Krimi",
        "Science-Fiction|Science Fiction|Sci-Fi" to "Science-Fiction",
        "Horrorfilm|Horror" to "Horror",
        "Western" to "Western",
        "Abenteuerfilm|Abenteuer" to "Abenteuer",
        "Actionfilm|Action-Thriller" to "Action",
        "Animationsfilm|Zeichentrick" to "Animation",
        "Liebesfilm|Romanze|Liebeskomödie" to "Liebesfilm",
        "Familienfilm" to "Familie",
        "Musikfilm|Musical" to "Musik",
        "Historienfilm|Biopic|Filmbiografie" to "Historie",
        "Dokumentarfilm" to "Dokumentarfilm",
        "Drama" to "Drama",
    ).map { (pattern, label) -> Regex("\\b(?:$pattern)", RegexOption.IGNORE_CASE) to label }

    fun genre(rawTitle: String, description: String): String? =
        genres.firstOrNull { (re, _) -> re.containsMatchIn(description) || re.containsMatchIn(rawTitle) }?.second
}
