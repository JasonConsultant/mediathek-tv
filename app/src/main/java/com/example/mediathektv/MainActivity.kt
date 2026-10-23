@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
// Navigation & Zustand
// ============================================================================

private sealed interface Screen {
    data object Home : Screen
    class SeriesView(val state: SeriesState) : Screen
    class Detail(val video: Video, val seriesName: String? = null) : Screen
}

/** Zustand der Startseite – bleibt erhalten, wenn man in Details geht und zurückkommt. */
private class HomeState {
    var films by mutableStateOf<List<Video>?>(null)
    var series by mutableStateOf<List<Series>?>(null)
    var crime by mutableStateOf<List<Video>?>(null)
    var docs by mutableStateOf<List<Video>?>(null)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<Video>?>(null)
    var searching by mutableStateOf(false)

    var preview by mutableStateOf<Preview?>(null)
    var focusKey by mutableStateOf<String?>(null)   // zuletzt fokussierte Kachel
    val column = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun rowState(key: String) = rows.getOrPut(key) { LazyListState() }

    val loading get() = films == null || series == null || crime == null || docs == null
    val failed get() = !loading && films!!.isEmpty() && series!!.isEmpty() && crime!!.isEmpty() && docs!!.isEmpty()

    suspend fun load() = coroutineScope {
        films = null; series = null; crime = null; docs = null
        launch { films = fetch { Catalog.films() } }
        launch { series = fetch { Catalog.series() } }
        launch { crime = fetch { Catalog.crime() } }
        launch { docs = fetch { Catalog.docs() } }
    }

    suspend fun search() {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return }
        searching = true
        results = fetch { Catalog.search(q) }
        searching = false
        results?.firstOrNull()?.let { focusKey = "search|${it.id}" }
    }

    private suspend fun <T> fetch(block: suspend () -> List<T>): List<T> =
        withContext(Dispatchers.Default) { runCatching { block() }.getOrDefault(emptyList()) }
}

private class SeriesState(val series: Series) {
    var episodes by mutableStateOf(series.episodes)
    var loading by mutableStateOf(true)
    var season by mutableStateOf<Int?>(null)        // null = alle Staffeln
    var focusKey by mutableStateOf<String?>(series.episodes.firstOrNull()?.id)
    val grid = LazyGridState()

    val seasons: List<Int> get() = episodes.mapNotNull { it.episode?.season }.distinct().sorted()
    val visible: List<Video> get() = season?.let { s -> episodes.filter { it.episode?.season == s } } ?: episodes

    suspend fun load() {
        val all = withContext(Dispatchers.Default) { runCatching { Catalog.episodes(series) }.getOrDefault(emptyList()) }
        if (all.isNotEmpty()) episodes = all
        if (episodes.none { it.id == focusKey }) focusKey = visible.firstOrNull()?.id
        loading = false
    }
}

private data class Preview(val overline: String, val title: String, val meta: String, val description: String)

private fun Video.preview() = Preview(topic, title, metaLine(this), description)
private fun Series.preview() = Preview(
    channel, name, "${episodes.size}+ Folgen",
    episodes.lastOrNull()?.description.orEmpty()
)

// ============================================================================
// App
// ============================================================================

@Composable
private fun App() {
    val home = remember { HomeState() }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var update by remember { mutableStateOf<Update?>(null) }

    LaunchedEffect(Unit) {
        launch { home.load() }
        update = Updater.check()
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                home = home,
                onVideo = { stack.add(Screen.Detail(it)) },
                onSeries = { stack.add(Screen.SeriesView(SeriesState(it))) },
                onSearch = { scope.launch { home.search() } },
                onRetry = { scope.launch { home.load() } },
            )
            is Screen.SeriesView -> SeriesScreen(
                state = screen.state,
                onVideo = { stack.add(Screen.Detail(it, screen.state.series.name)) },
            )
            is Screen.Detail -> key(screen) {
                DetailScreen(screen.video, screen.seriesName) { v, subtitles ->
                    PlayerActivity.start(context, v, subtitles)
                }
            }
        }
    }

    update?.let { u -> UpdateDialog(u, onDismiss = { update = null }) }
}

// ============================================================================
// Startseite
// ============================================================================

@Composable
private fun HomeScreen(
    home: HomeState,
    onVideo: (Video) -> Unit,
    onSeries: (Series) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    // Beim ersten Laden: Fokus auf die erste verfügbare Kachel
    val firstKey = home.films?.firstOrNull()?.let { "films|${it.id}" }
        ?: home.series?.firstOrNull()?.let { "series|${it.name}" }
    LaunchedEffect(firstKey) { if (home.focusKey == null && firstKey != null) home.focusKey = firstKey }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile mit Suche
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mediathek", color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SearchField(home.query, onChange = { home.query = it; if (it.isBlank()) home.results = null }, onSearch = onSearch)
        }

        // Vorschau der fokussierten Kachel
        Box(Modifier.fillMaxWidth().height(118.dp).padding(horizontal = 56.dp, vertical = 10.dp)) {
            home.preview?.let { p ->
                Column {
                    Text(p.overline.uppercase(), color = TvColors.Accent, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1)
                    Text(p.title, color = TvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.meta, color = TvColors.TextMuted, fontSize = 14.sp, maxLines = 1)
                    Text(p.description, color = TvColors.TextMuted, fontSize = 14.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.65f))
                }
            }
        }

        if (home.failed) {
            ErrorBox("Keine Inhalte geladen. Bitte Internetverbindung prüfen.", onRetry)
            return@Column
        }

        LazyColumn(
            state = home.column,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (home.searching || home.results != null) item(key = "search") {
                VideoRow("Suche: „${home.query}“", "search", home.results, home, onVideo)
            }
            item(key = "films") { VideoRow("Filme", "films", home.films, home, onVideo) }
            item(key = "series") { SeriesRow(home.series, home, onSeries) }
            item(key = "crime") { VideoRow("Krimi", "crime", home.crime, home, onVideo) }
            item(key = "docs") { VideoRow("Dokumentationen", "docs", home.docs, home, onVideo) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.width(380.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
            .border(2.dp, if (focused) TvColors.Accent else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) Text("Filme, Serien, Sendungen suchen …", color = TvColors.TextMuted, fontSize = 17.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TvColors.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(TvColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

@Composable
private fun RowTitle(text: String) =
    Text(text, color = TvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))

@Composable
private fun VideoRow(title: String, rowKey: String, videos: List<Video>?, home: HomeState, onVideo: (Video) -> Unit) {
    if (videos != null && videos.isEmpty()) {
        if (rowKey == "search") Column { RowTitle(title); Hint("Keine Treffer.") }
        return
    }
    Column {
        RowTitle(title)
        if (videos == null) { Hint("Lädt …"); return@Column }
        LazyRow(
            state = home.rowState(rowKey),
            contentPadding = PaddingValues(horizontal = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(videos.distinctBy { it.id }, key = { it.id }) { v ->
                val key = "$rowKey|${v.id}"
                VideoCard(
                    video = v,
                    overline = overlineOf(v),
                    title = v.title,
                    modifier = Modifier.restoreFocus(key, home.focusKey) {
                        home.focusKey = key; home.preview = v.preview()
                    },
                    onClick = { onVideo(v) },
                )
            }
        }
    }
}

@Composable
private fun SeriesRow(series: List<Series>?, home: HomeState, onSeries: (Series) -> Unit) {
    if (series != null && series.isEmpty()) return
    Column {
        RowTitle("Serien")
        if (series == null) { Hint("Lädt …"); return@Column }
        LazyRow(
            state = home.rowState("series"),
            contentPadding = PaddingValues(horizontal = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(series, key = { it.name }) { s ->
                val key = "series|${s.name}"
                TileCard(
                    channel = s.channel,
                    badges = listOf("${s.episodes.size}+ Folgen"),
                    overline = s.channel,
                    title = s.name,
                    modifier = Modifier.restoreFocus(key, home.focusKey) {
                        home.focusKey = key; home.preview = s.preview()
                    },
                    onClick = { onSeries(s) },
                )
            }
        }
    }
}

// ============================================================================
// Serie: Folgenübersicht
// ============================================================================

@Composable
private fun SeriesScreen(state: SeriesState, onVideo: (Video) -> Unit) {
    LaunchedEffect(state) { if (state.loading) state.load() }
    val s = state.series

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text(s.channel.uppercase(), color = TvColors.Accent, fontSize = 13.sp, letterSpacing = 1.sp)
        Text(s.name, color = TvColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            if (state.loading) "Folgen werden geladen …" else "${state.episodes.size} Folgen verfügbar",
            color = TvColors.TextMuted, fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (state.seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Chip("Alle", state.season == null) { state.season = null } }
                items(state.seasons) { n -> Chip("Staffel $n", state.season == n) { state.season = n } }
            }
            Spacer(Modifier.height(16.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state.grid,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            gridItems(state.visible.distinctBy { it.id }, key = { it.id }) { v ->
                VideoCard(
                    video = v,
                    overline = v.episode?.label ?: dateOf(v.timestamp),
                    title = Meta.episodeTitle(v.title, s.name),
                    modifier = Modifier.fillMaxWidth().restoreFocus(v.id, state.focusKey) { state.focusKey = it },
                    onClick = { onVideo(v) },
                )
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

    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(TvColors.channel(video.channel).copy(alpha = 0.35f), TvColors.Background))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
            Text((seriesName ?: video.topic).uppercase(), color = TvColors.Accent, fontSize = 14.sp, letterSpacing = 1.sp)
            Text(
                seriesName?.let { Meta.episodeTitle(video.title, it) } ?: video.title,
                color = TvColors.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                video.episode?.let { Info("Folge", it.label) }
                video.genre?.let { Info("Genre", it) }
                video.year?.let { Info("Jahr", it.toString()) }
                Info("Dauer", "${video.minutes} Min")
                Info("Sender", video.channel)
                Info("Ausgestrahlt", dateOf(video.timestamp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                video.description, color = TvColors.Text.copy(alpha = 0.85f), fontSize = 16.sp, lineHeight = 23.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.68f),
            )
            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(version, subtitles && version.hasSubtitles) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text("▶  Abspielen", fontSize = 18.sp)
                }
                if (version.hasSubtitles) {
                    Chip(if (subtitles) "Untertitel: An" else "Untertitel: Aus", subtitles) { subtitles = !subtitles }
                }
            }

            if (video.versions.size > 1) {
                Spacer(Modifier.height(22.dp))
                Text("Sprache / Fassung", color = TvColors.TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    video.versions.forEach { v -> Chip(v.language, v.id == version.id) { version = v } }
                }
            }
        }
    }
}

// ============================================================================
// Bausteine
// ============================================================================

@Composable
private fun VideoCard(video: Video, overline: String, title: String, modifier: Modifier, onClick: () -> Unit) {
    val badges = buildList {
        if (video.hasSubtitles) add("UT")
        if (video.variants.isNotEmpty()) add("${video.versions.size} Fassungen")
        add("${video.minutes} Min")
    }
    TileCard(video.channel, badges, overline, title, modifier, onClick)
}

@Composable
private fun TileCard(
    channel: String,
    badges: List<String>,
    overline: String,
    title: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick,
        modifier = modifier.width(300.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.Surface, focusedContainerColor = TvColors.SurfaceHigh),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TvColors.Accent), shape = shape)),
    ) {
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(Brush.linearGradient(listOf(TvColors.channel(channel), TvColors.Surface)))
        ) {
            Text(channel, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { Badge(it) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(overline, color = TvColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
        }
    }
}

@Composable
private fun Badge(text: String) = Text(
    text, color = Color.White, fontSize = 12.sp,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
        .padding(horizontal = 6.dp, vertical = 2.dp),
)

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) TvColors.Accent else TvColors.SurfaceHigh,
            contentColor = TvColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 15.sp) }
}

@Composable
private fun Info(label: String, value: String) = Column {
    Text(label, color = TvColors.TextMuted, fontSize = 12.sp)
    Text(value, color = TvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Hint(text: String) = Text(text, color = TvColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 56.dp))

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.padding(56.dp)) {
        Text(message, color = TvColors.Text, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
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
    LaunchedEffect(isTarget) {
        if (isTarget) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    }
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
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(TvColors.Surface).padding(36.dp)) {
            Text("Update verfügbar", color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    failed -> "Download fehlgeschlagen. Bitte später erneut versuchen."
                    else -> "Version ${update.versionName} ist da (installiert: ${BuildConfig.VERSION_NAME})."
                },
                color = TvColors.TextMuted, fontSize = 17.sp,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = {
                        busy = true; failed = false
                        scope.launch { failed = !Updater.downloadAndInstall(context, update); busy = false }
                    },
                    enabled = !busy,
                    modifier = Modifier.focusRequester(focus),
                ) { Text(if (busy) "Wird geladen …" else "Jetzt installieren", fontSize = 17.sp) }
                Button(onClick = onDismiss, enabled = !busy,
                    colors = ButtonDefaults.colors(containerColor = TvColors.SurfaceHigh)) {
                    Text("Später", fontSize = 17.sp)
                }
            }
        }
    }
}

// ============================================================================
// Formatierung
// ============================================================================

/** Bei Reihen (Tatort, …) der Reihenname, bei Sammel-Rubriken Genre und Jahr. */
private fun overlineOf(v: Video): String =
    (if (Catalog.isRubric(v.topic)) listOfNotNull(v.genre, v.year?.toString())
     else listOfNotNull(v.topic, v.year?.toString()))
        .joinToString(" · ").ifBlank { v.topic }

private fun dateOf(ts: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ts * 1000))

private fun metaLine(v: Video): String = buildList {
    v.genre?.let { add(it) }
    v.year?.let { add(it.toString()) }
    add("${v.minutes} Min")
    add(v.channel)
    add(dateOf(v.timestamp))
    if (v.hasSubtitles) add("Untertitel")
    if (v.variants.isNotEmpty()) add("${v.versions.size} Fassungen")
}.joinToString("  ·  ")
