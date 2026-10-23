package com.example.mediathektv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================================
// Navigation
// ============================================================================

/** Rubriken der Seitenleiste (Reihenfolge = Anzeige). */
enum class Tab(val label: String) {
    START("Start"), LIVE("TV-Sender"), SERIEN("Serien"), FILME("Filme"), KRIMIS("Krimis"), DOKUS("Dokus"),
    LISTE("Meine Liste"), SUCHE("Suche"), SETTINGS("Einstellungen"),
}

sealed interface Screen {
    data object Main : Screen
    class Browse(val state: BrowseState) : Screen
    class SeriesView(val state: SeriesState) : Screen
    /** [queue] = folgende Folgen; null = bei Bedarf aus der Serie [seriesId] nachladen. */
    class Detail(val video: Video, val seriesName: String? = null, val seriesId: String? = null, val queue: List<Video>? = null) : Screen
}

/** Von außen angestoßen: Startbildschirm ("Weiterschauen"-Eintrag) oder System-Suche. */
object Incoming {
    var playKey by mutableStateOf<String?>(null)
    var query by mutableStateOf<String?>(null)

    fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "mediathektv" }?.getQueryParameter("key")?.let { playKey = it }
            Intent.ACTION_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { query = it }
        }
    }
}

// ============================================================================
// Kacheln und Reihen
// ============================================================================

sealed interface Tile { val key: String }

class VideoTile(
    val video: Video,
    val overline: String,
    val title: String = video.title,
    val seriesName: String? = null,
    val seriesId: String? = null,
    /** aus Weiterschauen/Merkliste: beim Öffnen die aktuelle Fassung aus dem Katalog suchen */
    val saved: Boolean = false,
) : Tile { override val key = "v:${video.id}" }

class SeriesTile(val series: SeriesInfo) : Tile { override val key = "s:${series.id}" }
class LiveTile(val channel: LiveChannel) : Tile { override val key = "l:${channel.name}" }
class AllTile(val count: Int, val label: String, val spec: BrowseSpec?, val tab: Tab? = null) : Tile { override val key = "all" }

/** Eine Reihe auf einer Rubrik-Seite. */
class Shelf(val key: String, val title: String, val tiles: List<Tile>, val count: Int? = null)

/** Was "Alle anzeigen" öffnet: eine Liste von Sendungen oder von Serien, mit Filtern. */
class BrowseSpec(val title: String, val unit: String, val videos: List<Video> = emptyList(), val series: List<SeriesInfo> = emptyList()) {
    val isSeries get() = series.isNotEmpty()
}

data class Preview(val overline: String, val title: String, val meta: String, val description: String, val image: String? = null)

/** Zustand einer Rubrik-Seite (Fokus, Scrollpositionen, Vorschau) – bleibt beim Wechseln erhalten. */
class PageUi {
    var selectedChannel by mutableStateOf<LiveChannel?>(null)   // TV-Sender-Seite
    var focusKey by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Preview?>(null)
    val column = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun row(key: String) = rows.getOrPut(key) { LazyListState() }
}

enum class Sort(val label: String) { NEWEST("Neueste"), AZ("A–Z") }

class BrowseState(val spec: BrowseSpec) {
    var sort by mutableStateOf(Sort.NEWEST)
    var channel by mutableStateOf<String?>(null)
    var genre by mutableStateOf<String?>(null)
    var focusKey by mutableStateOf<String?>(null)
    val grid = LazyGridState()
}

class SeriesState(val info: SeriesInfo) {
    var episodes by mutableStateOf<List<Video>?>(null)
    var season by mutableStateOf<Int?>(null)
    var focusKey by mutableStateOf<String?>(null)
    val grid = LazyGridState()

    val seasons get() = episodes.orEmpty().mapNotNull { it.episode?.season }.distinct().sorted()
    val visible get() = episodes.orEmpty().let { e -> season?.let { s -> e.filter { it.episode?.season == s } } ?: e }

    suspend fun load(repo: CatalogRepository, country: String) {
        val all = repo.episodes(info.id)
        episodes = withContext(Dispatchers.Default) { all.mapNotNull { it.forCountry(country)?.withCover(info.image) } }
        focusKey = visible.firstOrNull()?.id
    }
}

class SearchResult(val videos: List<Video>, val series: List<SeriesInfo>, val live: List<Video>?)

// ============================================================================
// Katalog nach Land
// ============================================================================

/** Katalog nach Land gefiltert: nur, was dort auch abspielbar ist. */
class Filtered(
    val films: List<Video>, val crime: List<Video>, val docs: List<Video>,
    val series: List<SeriesInfo>, val filmSeries: List<SeriesInfo>, val docSeries: List<SeriesInfo>,
    val live: List<LiveChannel>,
) {
    private val dayAgo = System.currentTimeMillis() / 1000 - DAY

    /** Filme, Krimis, Dokus, die in den letzten 24 Stunden in den Katalog kamen – neueste zuerst. */
    val newVideos: List<Video> = (films + crime + docs).filter { it.firstSeen >= dayAgo }
        .distinctBy { it.id }.sortedByDescending { it.firstSeen }

    /** Serien und Reihen mit Folgen der letzten 24 Stunden. */
    val newSeries: List<SeriesInfo> = (series + filmSeries + docSeries).filter { it.newestEpisode >= dayAgo }
        .sortedByDescending { it.newestEpisode }
}

// ============================================================================
// App-Zustand
// ============================================================================

class AppState(context: Context) {
    private val appContext = context.applicationContext
    val repo = CatalogRepository(context)

    /** Tickt jede halbe Minute – für "Jetzt läuft" und Fortschrittsbalken im Programmführer. */
    var clock by mutableStateOf(nowSec())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var index by mutableStateOf<CatalogIndex?>(null)
    var loading by mutableStateOf(true)
    var detected by mutableStateOf<String?>(null)
    var chosen by mutableStateOf(prefs.getString("country", null))   // manuelle Wahl, null = automatisch

    val country: String get() = chosen ?: detected ?: Locale.getDefault().country.takeIf { it in Geo.countries } ?: "DE"

    val filtered: Filtered? by derivedStateOf {
        val i = index ?: return@derivedStateOf null
        val c = country
        Filtered(
            i.films.mapNotNull { it.forCountry(c) }, i.crime.mapNotNull { it.forCountry(c) }, i.docs.mapNotNull { it.forCountry(c) },
            i.series.filter { it.availableIn(c) }, i.filmSeries.filter { it.availableIn(c) }, i.docSeries.filter { it.availableIn(c) },
            // Senderangebot des gewählten Landes, dessen eigene Sender zuerst
            i.live.filter { it.availableIn(c) }.sortedBy { if (it.homeCountry == c) 0 else 1 },
        )
    }

    /** Läuft der Sender über den aktuellen Internetanschluss? (Die Länderwahl ändert daran nichts.) */
    fun playableHere(ch: LiveChannel) = ch.availableIn(detected ?: country)

    fun setCountry(code: String?) {
        chosen = code
        prefs.edit().apply { if (code == null) remove("country") else putString("country", code) }.apply()
    }

    // ---------- Navigation innerhalb der Hauptansicht ----------
    var tab by mutableStateOf(Tab.START)
    val pages = Tab.entries.associateWith { PageUi() }
    /**
     * Zähler: jede Erhöhung holt den Fokus auf die gemerkte Kachel der aktuellen Seite zurück
     * (Rückkehr von einer Unterseite, OK im Seitenmenü). Solange das Seitenmenü den Fokus hat,
     * holt keine Kachel den Fokus zu sich – sonst würde das Menü beim Durchblättern zuklappen.
     */
    var restoreTick by mutableIntStateOf(0)
    var menuFocused by mutableStateOf(false)
    var pageFocused = false      // irgendeine Kachel der Seite hat den Fokus (kein Compose-Zustand nötig)
    fun restoreFocus() { menuFocused = false; restoreTick++ }

    // ---------- Laden & automatisches Nachladen ----------
    private val mutex = Mutex()
    private var loadedAt = 0L
    private var lastCheck = prefs.getLong("lastCheck", 0L)
    private var lastUpdateCheck = 0L

    suspend fun start() = coroutineScope {
        loading = true
        launch { detected = CountryDetector.detect() }
        repo.cached()?.let { show(it) }
        sync(force = true)
        loading = false
        Epg.refresh(appContext)
    }

    suspend fun sync(force: Boolean = false) = coroutineScope { mutex.withLock {
        val now = System.currentTimeMillis()
        if (force || now - lastCheck >= CHECK_INTERVAL) {
            lastCheck = now
            prefs.edit().putLong("lastCheck", now).apply()
            launch { Epg.refresh(appContext) }
            repo.refresh()?.let { show(it); return@withLock }
        }
        if (repo.storedAt() > loadedAt) repo.cached()?.let { show(it) }
    } }

    private fun show(i: CatalogIndex) { index = i; loadedAt = repo.storedAt() }

    fun updateCheckDue(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_INTERVAL) return false
        lastUpdateCheck = now
        return true
    }

    // ---------- "Neu seit deinem letzten Besuch" ----------
    var sinceVisit by mutableStateOf(prefs.getLong("lastLeft", 0L).takeIf { it > 0 } ?: (nowSec() - DAY))
    fun onAppLeft() = prefs.edit().putLong("lastLeft", nowSec()).apply()
    fun onAppReturned() {
        val left = prefs.getLong("lastLeft", 0L)
        if (left > 0 && nowSec() - left >= 3600) sinceVisit = left
    }

    // ---------- App-Update ----------
    var update by mutableStateOf<Update?>(null)
    var updateStatus by mutableStateOf<String?>(null)

    suspend fun checkUpdateNow() {
        updateStatus = "Suche …"
        val found = Updater.check()
        update = found
        updateStatus = if (found == null) "Die App ist aktuell (${BuildConfig.VERSION_NAME})" else null
        delay(5000)
        if (updateStatus?.startsWith("Die App ist") == true) updateStatus = null
    }

    // ---------- Suche ----------
    var query by mutableStateOf("")
    var search by mutableStateOf<SearchResult?>(null)

    /** Sofort im Katalog suchen, dann zusätzlich live in allen Mediatheken. */
    suspend fun runSearch() {
        val q = query.trim()
        if (q.length < 2) { search = null; return }
        val f = filtered
        val needle = keyNorm(q)
        val local = withContext(Dispatchers.Default) {
            val videos = f?.let { it.films + it.crime + it.docs }.orEmpty()
                .filter { keyNorm(it.title).contains(needle) || keyNorm(it.topic).contains(needle) }
                .distinctBy { it.id }.take(60)
            val series = f?.let { it.series + it.filmSeries + it.docSeries }.orEmpty()
                .filter { keyNorm(it.name).contains(needle) }.take(40)
            SearchResult(videos, series, null)
        }
        search = local
        val live = runCatching { LiveSearch.search(q) }.getOrDefault(emptyList())
        val known = local.videos.map { keyNorm(it.title) }.toSet()
        search = SearchResult(local.videos, local.series, live.filter { keyNorm(it.title) !in known })
    }
}

// ============================================================================
// Hilfsfunktionen
// ============================================================================

const val DAY = 24 * 3600L
const val MENU_SWITCH_DELAY = 120L                 // ms Ruhe auf einem Menüpunkt, bis die Seite wechselt
const val CHECK_INTERVAL = 60 * 60 * 1000L        // höchstens stündlich im Netz nachsehen
const val UPDATE_INTERVAL = 6 * 60 * 60 * 1000L   // App-Updates alle 6 Stunden prüfen
const val SYNC_TICK = 15 * 60 * 1000L             // solange offen: alle 15 Minuten
fun nowSec() = System.currentTimeMillis() / 1000

/** Sendergruppen für Reihen und Filter. */
val channelGroups = linkedMapOf(
    "ARD & Dritte" to setOf("ARD", "ONE", "ARD-alpha", "BR", "HR", "MDR", "NDR", "RBB", "SR", "SWR", "WDR", "tagesschau24", "Radio Bremen TV", "PHOENIX"),
    "ZDF" to setOf("ZDF", "ZDFneo", "ZDFinfo"),
    "ARTE" to setOf("ARTE.DE"),
    "3sat" to setOf("3Sat"),
    "SRF" to setOf("SRF"),
    "ORF" to setOf("ORF"),
)
fun groupOf(channel: String) = channelGroups.entries.firstOrNull { channel in it.value }?.key ?: channel

/** Folgen ohne eigenes Bild bekommen das Titelbild der Serie. */
fun Video.withCover(cover: String?) = if (image == null && cover != null) copy(image = cover) else this

/** Folgen nach [v] in Serienreihenfolge – nur echte Folgen (mit Nummer) laufen automatisch weiter. */
fun queueAfter(episodes: List<Video>, v: Video): List<Video> {
    val i = episodes.indexOfFirst { it.id == v.id }
    return if (i < 0) emptyList() else episodes.drop(i + 1).filter { it.episode != null }.take(30)
}
