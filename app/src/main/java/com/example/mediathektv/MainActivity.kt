@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CatalogRefreshWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TvColors.Accent,
                    surface = TvColors.Surface,
                    background = TvColors.Background,
                )
            ) { App() }
        }
    }
}

// ============================================================================
// Kategorien, Navigation, Zustand
// ============================================================================

private enum class Category(val title: String, val unit: String) {
    FILMS("Filme", "Filme"),
    SERIES("Serien", "Serien"),
    CRIME("Krimis", "Krimis"),
    FILM_SERIES("Film-Reihen", "Reihen"),
    DOCS("Dokumentationen", "Dokus"),
    DOC_SERIES("Doku-Reihen", "Reihen");

    val isSeries get() = this == SERIES || this == FILM_SERIES || this == DOC_SERIES
}

private sealed interface Screen {
    data object Home : Screen
    class Browse(val state: BrowseState) : Screen
    class SeriesView(val state: SeriesState) : Screen
    class Detail(val video: Video, val seriesName: String? = null) : Screen
}

/** Katalog nach Land gefiltert: nur, was dort auch abspielbar ist. */
private class Filtered(
    val films: List<Video>, val crime: List<Video>, val docs: List<Video>,
    val series: List<SeriesInfo>, val filmSeries: List<SeriesInfo>, val docSeries: List<SeriesInfo>,
) {
    private val dayAgo = System.currentTimeMillis() / 1000 - DAY

    /** Filme, Krimis, Dokus, die in den letzten 24 Stunden in den Katalog kamen – neueste zuerst. */
    val newVideos: List<Video> = (films + crime + docs).filter { it.firstSeen >= dayAgo }
        .distinctBy { it.id }.sortedByDescending { it.firstSeen }

    /** Serien und Reihen mit Folgen der letzten 24 Stunden. */
    val newSeries: List<SeriesInfo> = (series + filmSeries + docSeries).filter { it.newestEpisode >= dayAgo }
        .sortedByDescending { it.newestEpisode }

    fun videos(c: Category) = when (c) { Category.FILMS -> films; Category.CRIME -> crime; Category.DOCS -> docs; else -> emptyList() }
    fun series(c: Category) = when (c) { Category.SERIES -> series; Category.FILM_SERIES -> filmSeries; Category.DOC_SERIES -> docSeries; else -> emptyList() }
    fun size(c: Category) = if (c.isSeries) series(c).size else videos(c).size
}

private class AppState(context: Context) {
    val repo = CatalogRepository(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var index by mutableStateOf<CatalogIndex?>(null)
    var loading by mutableStateOf(true)
    var detected by mutableStateOf<String?>(null)
    var chosen by mutableStateOf(prefs.getString("country", null))   // manuelle Wahl

    val country: String get() = chosen ?: detected ?: Locale.getDefault().country.takeIf { it in Geo.countries } ?: "CH"

    val filtered: Filtered? by derivedStateOf {
        val i = index ?: return@derivedStateOf null
        val c = country
        Filtered(
            i.films.mapNotNull { it.forCountry(c) }, i.crime.mapNotNull { it.forCountry(c) }, i.docs.mapNotNull { it.forCountry(c) },
            i.series.filter { it.availableIn(c) }, i.filmSeries.filter { it.availableIn(c) }, i.docSeries.filter { it.availableIn(c) },
        )
    }

    fun nextCountry() {
        val next = Geo.countries[(Geo.countries.indexOf(country) + 1) % Geo.countries.size]
        chosen = next
        prefs.edit().putString("country", next).apply()
    }

    // ---------- Laden & automatisches Nachladen ----------
    private val mutex = Mutex()
    private var loadedAt = 0L                                   // Stand des angezeigten Katalogs (Dateizeit)
    private var lastCheck = prefs.getLong("lastCheck", 0L)      // letzte Prüfung im Netz (ms)
    private var lastUpdateCheck = 0L

    /** Beim Start: gespeicherten Katalog sofort zeigen, dann im Netz nach einem neuen schauen. */
    suspend fun start() = coroutineScope {
        loading = true
        launch { detected = CountryDetector.detect() }
        repo.cached()?.let { show(it) }
        sync(force = true)
        loading = false
    }

    /**
     * Holt einen neuen Katalog, wenn die letzte Prüfung länger als [CHECK_INTERVAL] her ist,
     * und übernimmt einen Katalog, den der Hintergrund-Job inzwischen gespeichert hat.
     */
    suspend fun sync(force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (force || now - lastCheck >= CHECK_INTERVAL) {
            lastCheck = now
            prefs.edit().putLong("lastCheck", now).apply()
            repo.refresh()?.let { show(it); return@withLock }
        }
        if (repo.storedAt() > loadedAt) repo.cached()?.let { show(it) }
    }

    private fun show(i: CatalogIndex) { index = i; loadedAt = repo.storedAt() }

    fun updateCheckDue(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_INTERVAL) return false
        lastUpdateCheck = now
        return true
    }

    // ---------- "Neu seit deinem letzten Besuch" ----------
    /** Alles, was nach diesem Zeitpunkt (Unix-Sekunden) in den Katalog kam, bekommt ein NEU-Abzeichen. */
    var sinceVisit by mutableStateOf(prefs.getLong("lastLeft", 0L).takeIf { it > 0 } ?: (nowSec() - DAY))

    /** App wird verlassen (Home-Taste, anderer Sender …) – nicht beim Abspielen eines Films. */
    fun onAppLeft() = prefs.edit().putLong("lastLeft", nowSec()).apply()

    /** App kommt zurück: nach mindestens einer Stunde Abwesenheit zählt das als neuer Besuch. */
    fun onAppReturned() {
        val left = prefs.getLong("lastLeft", 0L)
        if (left > 0 && nowSec() - left >= 3600) sinceVisit = left
    }

    // Startseite
    val home = HomeUi()
    // Suche
    var query by mutableStateOf("")
    var search by mutableStateOf<SearchResult?>(null)
}

private class HomeUi {
    var preview by mutableStateOf<Preview?>(null)
    var focusKey by mutableStateOf<String?>(null)
    val column = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun row(key: String) = rows.getOrPut(key) { LazyListState() }
}

private class SearchResult(val videos: List<Video>, val series: List<SeriesInfo>, val live: List<Video>?)

private enum class Sort(val label: String) { NEWEST("Neueste"), AZ("A–Z") }

/** Zustand der "Alle anzeigen"-Übersicht – bleibt erhalten, wenn man Details öffnet. */
private class BrowseState(val category: Category) {
    var sort by mutableStateOf(Sort.NEWEST)
    var channel by mutableStateOf<String?>(null)
    var genre by mutableStateOf<String?>(null)
    var focusKey by mutableStateOf<String?>(null)
    val grid = LazyGridState()
}

private class SeriesState(val info: SeriesInfo) {
    var episodes by mutableStateOf<List<Video>?>(null)
    var season by mutableStateOf<Int?>(null)
    var focusKey by mutableStateOf<String?>(null)
    val grid = LazyGridState()

    val seasons get() = episodes.orEmpty().mapNotNull { it.episode?.season }.distinct().sorted()
    val visible get() = episodes.orEmpty().let { e -> season?.let { s -> e.filter { it.episode?.season == s } } ?: e }

    suspend fun load(repo: CatalogRepository, country: String) {
        val all = repo.episodes(info.id)
        episodes = withContext(Dispatchers.Default) { all.mapNotNull { it.forCountry(country) } }
        focusKey = visible.firstOrNull()?.id
    }
}

private data class Preview(val overline: String, val title: String, val meta: String, val description: String)

/** Sendergruppen für den Filter. */
private val channelGroups = linkedMapOf(
    "ARD & Dritte" to setOf("ARD", "ONE", "ARD-alpha", "BR", "HR", "MDR", "NDR", "RBB", "SR", "SWR", "WDR", "tagesschau24", "Radio Bremen TV"),
    "ZDF" to setOf("ZDF", "ZDFneo", "ZDFinfo"),
    "ARTE" to setOf("ARTE.DE"),
    "3sat" to setOf("3Sat"),
    "SRF" to setOf("SRF"),
    "ORF" to setOf("ORF"),
    "phoenix" to setOf("PHOENIX"),
)
private fun groupOf(channel: String) = channelGroups.entries.firstOrNull { channel in it.value }?.key ?: channel

// ============================================================================
// App
// ============================================================================

@Composable
private fun App() {
    val context = LocalContext.current
    val app = remember { AppState(context.applicationContext) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Update?>(null) }

    LaunchedEffect(Unit) { app.start() }

    // Solange die Startseite sichtbar ist: bei Rückkehr und alle 15 Minuten nach Neuem schauen.
    // Während ein Film läuft (Player im Vordergrund) ruht das.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!app.loading) app.sync()
                if (app.updateCheckDue()) Updater.check()?.let { update = it }
                delay(SYNC_TICK)
            }
        }
    }

    // App als Ganzes verlassen / zurückgekehrt (für "Neu seit deinem letzten Besuch")
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> app.onAppLeft()
                Lifecycle.Event.ON_START -> app.onAppReturned()
                else -> Unit
            }
        }
        val process = ProcessLifecycleOwner.get().lifecycle
        process.addObserver(observer)
        onDispose { process.removeObserver(observer) }
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    fun openVideo(v: Video, series: String? = null) = stack.add(Screen.Detail(v, series))
    fun openSeries(s: SeriesInfo) = stack.add(Screen.SeriesView(SeriesState(s)))
    fun openAll(c: Category) = stack.add(Screen.Browse(BrowseState(c)))

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                app, onVideo = { openVideo(it) }, onSeries = ::openSeries, onAll = ::openAll,
                onSearch = { scope.launch { runSearch(app) } },
                onRetry = { scope.launch { app.start() } },
            )
            is Screen.Browse -> BrowseScreen(app, screen.state, onVideo = { openVideo(it) }, onSeries = ::openSeries)
            is Screen.SeriesView -> SeriesScreen(app, screen.state) { openVideo(it, screen.state.info.name) }
            is Screen.Detail -> key(screen) {
                DetailScreen(screen.video, screen.seriesName) { v, subtitles -> PlayerActivity.start(context, v, subtitles) }
            }
        }
    }
    update?.let { UpdateDialog(it) { update = null } }
}

/** Suche: sofort im Katalog, dann zusätzlich live im gesamten Index. */
private suspend fun runSearch(app: AppState) {
    val q = app.query.trim()
    if (q.length < 2) { app.search = null; return }
    val f = app.filtered
    val needle = norm(q)
    val local = withContext(Dispatchers.Default) {
        val videos = f?.let { (it.films + it.crime + it.docs) }.orEmpty()
            .filter { norm(it.title).contains(needle) || norm(it.topic).contains(needle) }
            .distinctBy { it.id }.take(60)
        val series = f?.let { it.series + it.filmSeries + it.docSeries }.orEmpty()
            .filter { norm(it.name).contains(needle) }.take(40)
        SearchResult(videos, series, null)
    }
    app.search = local
    app.home.focusKey = local.series.firstOrNull()?.let { "s-search|${it.id}" } ?: local.videos.firstOrNull()?.let { "v-search|${it.id}" }
    val live = runCatching { LiveSearch.search(q) }.getOrDefault(emptyList())
    val known = local.videos.map { norm(it.title) }.toSet()
    app.search = SearchResult(local.videos, local.series, live.filter { norm(it.title) !in known })
}

// ============================================================================
// Startseite
// ============================================================================

@Composable
private fun HomeScreen(
    app: AppState,
    onVideo: (Video) -> Unit,
    onSeries: (SeriesInfo) -> Unit,
    onAll: (Category) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    val f = app.filtered
    val ui = app.home
    val firstKey = f?.films?.firstOrNull()?.let { "v-${Category.FILMS}|${it.id}" }
    LaunchedEffect(firstKey) { if (ui.focusKey == null && firstKey != null) ui.focusKey = firstKey }

    Column(Modifier.fillMaxSize()) {
        Header(app, onSearch)

        // Vorschau der fokussierten Kachel
        Box(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 48.dp, vertical = 8.dp)) {
            ui.preview?.let { p ->
                Column {
                    Text(p.overline.uppercase(), color = TvColors.Accent, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1)
                    Text(p.title, color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.meta, color = TvColors.TextMuted, fontSize = 13.sp, maxLines = 1)
                    Text(p.description, color = TvColors.TextMuted, fontSize = 13.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.65f))
                }
            }
        }

        if (f == null) {
            if (app.loading) Hint("Katalog wird geladen …")
            else ErrorBox("Katalog konnte nicht geladen werden. Bitte Internetverbindung prüfen.", onRetry)
            return@Column
        }

        LazyColumn(
            state = ui.column,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            app.search?.let { s ->
                item(key = "search-series") { SeriesRow("Suche: Serien & Reihen", "search", s.series, null, app, onSeries) }
                item(key = "search-videos") { VideoRow("Suche: Sendungen", "search", s.videos, null, app, onVideo) }
                item(key = "search-live") {
                    if (s.live == null) Column { RowTitle("Suche in allen Mediatheken"); Hint("Sucht …") }
                    else VideoRow("Weitere Treffer in allen Mediatheken", "live", s.live, null, app, onVideo)
                }
            }
            if (f.newVideos.isNotEmpty()) item(key = "new-videos") {
                VideoRow("Neu seit gestern", "new", f.newVideos.take(40), null, app, onVideo, count = f.newVideos.size)
            }
            if (f.newSeries.isNotEmpty()) item(key = "new-series") {
                SeriesRow("Neue Folgen", "newsrs", f.newSeries.take(40), null, app, onSeries, count = f.newSeries.size)
            }
            for (c in Category.entries) item(key = c.name) {
                if (c.isSeries) SeriesRow(c.title, c.name, f.series(c).take(ROW_SIZE), f.size(c), app, onSeries) { onAll(c) }
                else VideoRow(c.title, c.name, f.videos(c).take(ROW_SIZE), f.size(c), app, onVideo) { onAll(c) }
            }
        }
    }
}

private const val ROW_SIZE = 24
private const val DAY = 24 * 3600L
private const val CHECK_INTERVAL = 60 * 60 * 1000L        // höchstens stündlich im Netz nachsehen
private const val UPDATE_INTERVAL = 6 * 60 * 60 * 1000L   // App-Updates alle 6 Stunden prüfen
private const val SYNC_TICK = 15 * 60 * 1000L             // solange offen: alle 15 Minuten
private fun nowSec() = System.currentTimeMillis() / 1000

@Composable
private fun Header(app: AppState, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Mediathek", color = TvColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            app.index?.let {
                Text("Katalog vom ${dateOf(it.generated)}", color = TvColors.TextMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Chip("Land: ${Geo.name(app.country)}", selected = false) { app.nextCountry() }
        Spacer(Modifier.width(12.dp))
        SearchField(app.query, onChange = { app.query = it; if (it.isBlank()) app.search = null }, onSearch = onSearch)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.width(340.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
            .border(2.dp, if (focused) TvColors.Accent else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) Text("Filme, Serien, Sendungen suchen …", color = TvColors.TextMuted, fontSize = 16.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TvColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(TvColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

@Composable
private fun RowTitle(text: String) =
    Text(text, color = TvColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))

/** Reihe mit Sendungen; [total] != null zeigt am Ende eine "Alle anzeigen"-Kachel. */
@Composable
private fun VideoRow(
    title: String, rowKey: String, videos: List<Video>, total: Int?,
    app: AppState, onVideo: (Video) -> Unit, count: Int? = total, onAll: () -> Unit = {},
) {
    if (videos.isEmpty()) return
    val ui = app.home
    Column {
        RowTitle(if (count != null) "$title  ·  $count" else title)
        LazyRow(state = ui.row(rowKey), contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(videos.distinctBy { it.id }, key = { it.id }) { v ->
                val key = "v-$rowKey|${v.id}"
                VideoCard(v, overlineOf(v), v.title, app.sinceVisit,
                    Modifier.width(CARD_WIDTH).restoreFocus(key, ui.focusKey) { ui.focusKey = key; ui.preview = v.preview() }) { onVideo(v) }
            }
            if (total != null && total > videos.size) item(key = "all") {
                val key = "all-$rowKey"
                AllCard(total, title, Modifier.width(CARD_WIDTH).restoreFocus(key, ui.focusKey) { ui.focusKey = key; ui.preview = null }, onAll)
            }
        }
    }
}

@Composable
private fun SeriesRow(
    title: String, rowKey: String, series: List<SeriesInfo>, total: Int?,
    app: AppState, onSeries: (SeriesInfo) -> Unit, count: Int? = total, onAll: () -> Unit = {},
) {
    if (series.isEmpty()) return
    val ui = app.home
    Column {
        RowTitle(if (count != null) "$title  ·  $count" else title)
        LazyRow(state = ui.row(rowKey), contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(series, key = { it.id }) { s ->
                val key = "s-$rowKey|${s.id}"
                SeriesCard(s, app.country, app.sinceVisit,
                    Modifier.width(CARD_WIDTH).restoreFocus(key, ui.focusKey) { ui.focusKey = key; ui.preview = s.preview(app.country) }) { onSeries(s) }
            }
            if (total != null && total > series.size) item(key = "all") {
                val key = "all-$rowKey"
                AllCard(total, title, Modifier.width(CARD_WIDTH).restoreFocus(key, ui.focusKey) { ui.focusKey = key; ui.preview = null }, onAll)
            }
        }
    }
}

// ============================================================================
// Alle anzeigen: Raster mit Filtern
// ============================================================================

@Composable
private fun BrowseScreen(app: AppState, state: BrowseState, onVideo: (Video) -> Unit, onSeries: (SeriesInfo) -> Unit) {
    val f = app.filtered ?: return
    val c = state.category
    val videos = f.videos(c)
    val series = f.series(c)

    // Filteroptionen aus den vorhandenen Daten
    val channels = remember(f, c) {
        (if (c.isSeries) series.map { groupOf(it.channel) } else videos.map { groupOf(it.channel) })
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val genres = remember(f, c) {
        if (c.isSeries) emptyList()
        else videos.mapNotNull { it.genre }.groupingBy { it }.eachCount().filter { it.value >= 5 }
            .entries.sortedByDescending { it.value }.map { it.key }.take(10)
    }

    val shownVideos = remember(f, c, state.sort, state.channel, state.genre) {
        videos.filter { (state.channel == null || groupOf(it.channel) == state.channel) && (state.genre == null || it.genre == state.genre) }
            .let { l -> if (state.sort == Sort.AZ) l.sortedBy { it.title.lowercase() } else l }
    }
    val shownSeries = remember(f, c, state.sort, state.channel) {
        series.filter { state.channel == null || groupOf(it.channel) == state.channel }
            .let { l -> if (state.sort == Sort.AZ) l.sortedBy { it.name.lowercase() } else l }
    }
    val count = if (c.isSeries) shownSeries.size else shownVideos.size
    LaunchedEffect(state.focusKey == null, count) {
        if (state.focusKey == null) state.focusKey = shownSeries.firstOrNull()?.id ?: shownVideos.firstOrNull()?.id
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(c.title, color = TvColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("$count ${c.unit} verfügbar in ${Geo.name(app.country)}", color = TvColors.TextMuted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        FilterRow {
            Sort.entries.forEach { s -> item { Chip(s.label, state.sort == s) { state.sort = s } } }
            item { Spacer(Modifier.width(16.dp)) }
            item { Chip("Alle Sender", state.channel == null) { state.channel = null } }
            items(channels) { ch -> Chip(ch, state.channel == ch) { state.channel = if (state.channel == ch) null else ch } }
        }
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FilterRow {
                item { Chip("Alle Genres", state.genre == null) { state.genre = null } }
                items(genres) { g -> Chip(g, state.genre == g) { state.genre = if (state.genre == g) null else g } }
            }
        }
        Spacer(Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state.grid,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (c.isSeries) gridItems(shownSeries, key = { it.id }) { s ->
                SeriesCard(s, app.country, app.sinceVisit, Modifier.fillMaxWidth().restoreFocus(s.id, state.focusKey) { state.focusKey = it }) { onSeries(s) }
            } else gridItems(shownVideos, key = { it.id }) { v ->
                VideoCard(v, overlineOf(v), v.title, app.sinceVisit, Modifier.fillMaxWidth().restoreFocus(v.id, state.focusKey) { state.focusKey = it }) { onVideo(v) }
            }
        }
    }
}

@Composable
private fun FilterRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

// ============================================================================
// Serie / Reihe: Folgen
// ============================================================================

@Composable
private fun SeriesScreen(app: AppState, state: SeriesState, onVideo: (Video) -> Unit) {
    val country = app.country
    LaunchedEffect(state, country) { if (state.episodes == null) state.load(app.repo, country) }
    val s = state.info
    val unit = if (s.kind == SeriesKind.FILM_SERIES) "Filme" else "Folgen"

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("${kindLabel(s.kind)} · ${s.channel}".uppercase(), color = TvColors.Accent, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(s.name, color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            state.episodes?.let { "${it.size} $unit verfügbar in ${Geo.name(country)}" } ?: "$unit werden geladen …",
            color = TvColors.TextMuted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (state.seasons.size > 1) {
            FilterRow {
                item { Chip("Alle", state.season == null) { state.season = null } }
                items(state.seasons) { n -> Chip("Staffel $n", state.season == n) { state.season = n } }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.episodes?.isEmpty() == true) Hint("Keine $unit verfügbar.")

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state.grid,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            gridItems(state.visible.distinctBy { it.id }, key = { it.id }) { v ->
                VideoCard(
                    video = v,
                    overline = v.episode?.label ?: listOfNotNull(v.year?.toString(), dateOf(v.timestamp)).first(),
                    title = Meta.episodeTitle(v.title, s.name),
                    newSince = app.sinceVisit,
                    modifier = Modifier.fillMaxWidth().restoreFocus(v.id, state.focusKey) { state.focusKey = it },
                ) { onVideo(v) }
            }
        }
    }
}

// ============================================================================
// Detailansicht
// ============================================================================

@Composable
private fun DetailScreen(video: Video, seriesName: String?, onPlay: (Video, Boolean) -> Unit) {
    var version by remember { mutableStateOf(video) }
    var subtitles by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(
        Brush.horizontalGradient(listOf(TvColors.channel(video.channel).copy(alpha = 0.35f), TvColors.Background))
    )) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Text((seriesName ?: video.topic).uppercase(), color = TvColors.Accent, fontSize = 13.sp, letterSpacing = 1.sp)
            Text(
                seriesName?.let { Meta.episodeTitle(video.title, it) } ?: video.title,
                color = TvColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                video.episode?.let { Info("Folge", it.label) }
                video.genre?.let { Info("Genre", it) }
                video.year?.let { Info("Jahr", it.toString()) }
                Info("Dauer", "${video.minutes} Min")
                Info("Sender", video.channel)
                Info("Ausgestrahlt", dateOf(video.timestamp))
            }
            Spacer(Modifier.height(16.dp))
            Text(video.description, color = TvColors.Text.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp,
                maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.68f))
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(version, subtitles && version.hasSubtitles) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text("▶  Abspielen", fontSize = 17.sp)
                }
                if (version.hasSubtitles) Chip(if (subtitles) "Untertitel: An" else "Untertitel: Aus", subtitles) { subtitles = !subtitles }
            }
            if (video.versions.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("Sprache / Fassung", color = TvColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    video.versions.forEach { v -> Chip(v.language, v.id == version.id) { version = v } }
                }
            }
            if (!video.geoKnown) {
                Spacer(Modifier.height(16.dp))
                Text("Hinweis: Treffer aus der Live-Suche – die Ländersperre ist unbekannt.", color = TvColors.TextMuted, fontSize = 13.sp)
            }
        }
    }
}

// ============================================================================
// Bausteine
// ============================================================================

private val CARD_WIDTH = 232.dp

@Composable
private fun VideoCard(video: Video, overline: String, title: String, newSince: Long, modifier: Modifier, onClick: () -> Unit) {
    val badges = buildList {
        if (video.hasSubtitles) add("UT")
        if (video.variants.isNotEmpty()) add("${video.versions.size} Fassungen")
        add("${video.minutes} Min")
    }
    val isNew = video.firstSeen > 0 && video.firstSeen > newSince
    TileCard(video.channel, badges, overline, title, modifier, if (isNew) "NEU" else null, onClick)
}

@Composable
private fun SeriesCard(s: SeriesInfo, country: String, newSince: Long, modifier: Modifier, onClick: () -> Unit) {
    val films = s.kind == SeriesKind.FILM_SERIES
    val isNew = s.newestEpisode > 0 && s.newestEpisode > newSince
    TileCard(
        s.channel,
        listOf("${s.countIn(country)} " + if (films) "Filme" else "Folgen"),
        "${kindLabel(s.kind)}${if (s.crime) " · Krimi" else ""}",
        s.name, modifier,
        if (isNew) (if (films) "NEUER FILM" else "NEUE FOLGEN") else null,
        onClick,
    )
}

@Composable
private fun AllCard(total: Int, title: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick, modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.SurfaceHigh, focusedContainerColor = TvColors.Accent),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(Modifier.fillMaxWidth().height(CARD_IMAGE + 62.dp).padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Alle anzeigen  →", color = TvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("$total $title", color = TvColors.Text.copy(alpha = 0.8f), fontSize = 14.sp)
        }
    }
}

private val CARD_IMAGE = 128.dp

@Composable
private fun TileCard(
    channel: String, badges: List<String>, overline: String, title: String, modifier: Modifier,
    highlight: String? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick, modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.Surface, focusedContainerColor = TvColors.SurfaceHigh),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TvColors.Accent), shape = shape)),
    ) {
        Box(Modifier.fillMaxWidth().height(CARD_IMAGE)
            .background(Brush.linearGradient(listOf(TvColors.channel(channel), TvColors.Surface)))) {
            Text(channel, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            highlight?.let {
                Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(5.dp))
                        .background(TvColors.New).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                badges.forEach { Badge(it) }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(overline, color = TvColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, color = TvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(38.dp))
        }
    }
}

@Composable
private fun Badge(text: String) = Text(
    text, color = Color.White, fontSize = 11.sp,
    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f))
        .padding(horizontal = 5.dp, vertical = 1.dp),
)

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) TvColors.Accent else TvColors.SurfaceHigh,
            contentColor = TvColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun Info(label: String, value: String) = Column {
    Text(label, color = TvColors.TextMuted, fontSize = 12.sp)
    Text(value, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Hint(text: String) = Text(text, color = TvColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp))

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.padding(48.dp)) {
        Text(message, color = TvColors.Text, fontSize = 17.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Erneut versuchen") }
    }
}

/**
 * Merkt sich die fokussierte Kachel und holt den Fokus zurück, wenn man von einer
 * Unterseite zurückkehrt – sonst "zeigt" die Fernbedienung ins Leere.
 */
@Composable
private fun Modifier.restoreFocus(key: String, target: String?, onFocused: (String) -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    val isTarget = key == target
    LaunchedEffect(isTarget) { if (isTarget) { withFrameNanos { }; runCatching { requester.requestFocus() } } }
    return this.focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused(key) }
}

@Composable
private fun UpdateDialog(update: Update, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.width(500.dp).clip(RoundedCornerShape(18.dp)).background(TvColors.Surface).padding(32.dp)) {
            Text("Update verfügbar", color = TvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (failed) "Download fehlgeschlagen. Bitte später erneut versuchen."
                else "Version ${update.versionName} ist da (installiert: ${BuildConfig.VERSION_NAME}).",
                color = TvColors.TextMuted, fontSize = 16.sp,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { busy = true; failed = false; scope.launch { failed = !Updater.downloadAndInstall(context, update); busy = false } },
                    enabled = !busy, modifier = Modifier.focusRequester(focus),
                ) { Text(if (busy) "Wird geladen …" else "Jetzt installieren", fontSize = 16.sp) }
                Button(onClick = onDismiss, enabled = !busy, colors = ButtonDefaults.colors(containerColor = TvColors.SurfaceHigh)) {
                    Text("Später", fontSize = 16.sp)
                }
            }
        }
    }
}

// ============================================================================
// Texte & Formatierung
// ============================================================================

private fun kindLabel(k: SeriesKind) = when (k) {
    SeriesKind.SERIES -> "Serie"; SeriesKind.FILM_SERIES -> "Film-Reihe"; SeriesKind.DOC_SERIES -> "Doku-Reihe"
}

/** Bei Reihen (Tatort …) der Reihenname, bei Sammel-Rubriken Genre und Jahr. */
private fun overlineOf(v: Video): String =
    (if (Meta.isRubric(v.topic)) listOfNotNull(v.genre, v.year?.toString()) else listOfNotNull(v.topic, v.year?.toString()))
        .joinToString(" · ").ifBlank { v.topic }

private fun Video.preview() = Preview(topic, title, metaLine(this), description)

private fun SeriesInfo.preview(country: String) = Preview(
    "${kindLabel(kind)} · $channel", name,
    "${countIn(country)} " + (if (kind == SeriesKind.FILM_SERIES) "Filme" else "Folgen"), description,
)

private fun metaLine(v: Video): String = buildList {
    v.genre?.let { add(it) }
    v.year?.let { add(it.toString()) }
    add("${v.minutes} Min")
    add(v.channel)
    add(dateOf(v.timestamp))
    if (v.hasSubtitles) add("Untertitel")
    if (v.variants.isNotEmpty()) add("${v.versions.size} Fassungen")
}.joinToString("  ·  ")

private fun dateOf(ts: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ts * 1000))

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
