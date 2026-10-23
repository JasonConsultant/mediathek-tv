@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

/** Navigation aus den Seiten heraus. */
interface Nav {
    fun open(tile: Tile)
    fun browse(spec: BrowseSpec)
    fun tab(tab: Tab)
}

private const val ROW = 24

// ============================================================================
// Reihen je Rubrik
// ============================================================================

/**
 * Reihe mit Sendungen. [chronological] = Reihenfolge beibehalten (für "Neu …"-Reihen);
 * sonst stehen Sendungen mit Vorschaubild vorne – das sieht deutlich besser aus.
 */
private fun videoRow(key: String, title: String, videos: List<Video>, unit: String = "Sendungen", limit: Int = ROW,
                     chronological: Boolean = false): Shelf? {
    if (videos.isEmpty()) return null
    val shown = if (chronological) videos.take(limit) else videos.sortedByDescending { it.image != null }.take(limit)
    val tiles = shown.map { VideoTile(it, overlineOf(it)) } +
        if (videos.size > limit) listOf(AllTile(videos.size, unit, BrowseSpec(title, unit, videos = videos))) else emptyList()
    return Shelf(key, title, tiles, videos.size)
}

private fun seriesRow(key: String, title: String, series: List<SeriesInfo>, unit: String = "Serien", limit: Int = ROW): Shelf? {
    if (series.isEmpty()) return null
    val tiles = series.take(limit).map { SeriesTile(it) } +
        if (series.size > limit) listOf(AllTile(series.size, unit, BrowseSpec(title, unit, series = series))) else emptyList()
    return Shelf(key, title, tiles, series.size)
}

private val genrePlural = linkedMapOf(
    "Komödie" to "Komödien", "Drama" to "Dramen", "Thriller" to "Thriller", "Liebesfilm" to "Liebesfilme",
    "Tragikomödie" to "Tragikomödien", "Krimi" to "Krimis", "Abenteuer" to "Abenteuer", "Familie" to "Familienfilme",
    "Historie" to "Historienfilme", "Science-Fiction" to "Science-Fiction", "Western" to "Western", "Horror" to "Horror",
    "Musik" to "Musikfilme", "Animation" to "Animationsfilme",
)

private val docThemes = linkedMapOf(
    "Natur & Tiere" to Regex("natur|tier|expedition|universum|wildnis|ozean|meer|wald|alpen", RegexOption.IGNORE_CASE),
    "Geschichte" to Regex("geschichte|history|historie|krieg|jahrhundert|antike|römer|mittelalter|ddr", RegexOption.IGNORE_CASE),
    "Wissenschaft & Technik" to Regex("wissenschaft|terra x|lesch|technik|forschung|medizin|gesundheit|weltall|planet", RegexOption.IGNORE_CASE),
    "Gesellschaft & Politik" to Regex("gesellschaft|politik|reportage|37 grad|menschen|leben", RegexOption.IGNORE_CASE),
    "Kultur & Musik" to Regex("kultur|kunst|musik|literatur|architektur|film", RegexOption.IGNORE_CASE),
)

private fun channelRows(prefix: String, videos: List<Video>, unit: String, min: Int = 8): List<Shelf> =
    channelGroups.keys.mapNotNull { g ->
        val list = videos.filter { groupOf(it.channel) == g }
        if (list.size >= min) videoRow("$prefix-$g", "$g", list, unit) else null
    }

/** Inhalt einer Rubrik als Reihen (ohne die persönlichen Reihen, die kommen dynamisch dazu). */
fun buildShelves(tab: Tab, f: Filtered): List<Shelf> = when (tab) {
    Tab.START -> listOfNotNull(
        videoRow("new", "Neu seit gestern", f.newVideos, chronological = true),
        seriesRow("newsrs", "Neue Folgen", f.newSeries, "Serien und Reihen"),
        f.live.takeIf { it.isNotEmpty() }?.let { live ->
            Shelf("live", "Live-TV", live.take(12).map { LiveTile(it) } + AllTile(live.size, "Sender", null, Tab.LIVE), live.size)
        },
        videoRow("films", "Filme", f.films, "Filme"),
        seriesRow("series", "Serien", f.series),
        videoRow("crime", "Krimis", f.crime, "Krimis"),
        seriesRow("filmseries", "Film-Reihen", f.filmSeries, "Reihen"),
        videoRow("docs", "Dokumentationen", f.docs, "Dokus"),
    )

    Tab.FILME -> buildList {
        videoRow("f-new", "Neu in der Mediathek", f.films, "Filme", chronological = true)?.let(::add)
        for ((g, label) in genrePlural) {
            val list = f.films.filter { it.genre == g }
            if (list.size >= 6) videoRow("f-$g", label, list, label)?.let(::add)
        }
        seriesRow("f-reihen", "Film-Reihen", f.filmSeries.filter { !it.crime }, "Reihen")?.let(::add)
        addAll(channelRows("f", f.films, "Filme"))
    }

    Tab.SERIEN -> buildList {
        seriesRow("s-new", "Neue Folgen", f.series.filter { it.newestEpisode >= nowSec() - DAY })?.let(::add)
        seriesRow("s-all", "Alle Serien", f.series)?.let(::add)
        seriesRow("s-crime", "Krimiserien", f.series.filter { it.crime })?.let(::add)
        for (g in channelGroups.keys) {
            val list = f.series.filter { groupOf(it.channel) == g }
            if (list.size >= 6) seriesRow("s-$g", "Serien · $g", list)?.let(::add)
        }
        seriesRow("s-docs", "Doku-Reihen", f.docSeries, "Reihen")?.let(::add)
    }

    Tab.KRIMIS -> buildList {
        videoRow("k-new", "Neue Krimis", f.crime, "Krimis", chronological = true)?.let(::add)
        videoRow("k-tatort", "Tatort", f.crime.filter { it.topic.startsWith("Tatort") }, "Tatorte")?.let(::add)
        videoRow("k-polizeiruf", "Polizeiruf 110", f.crime.filter { it.topic.startsWith("Polizeiruf") }, "Filme")?.let(::add)
        seriesRow("k-reihen", "Krimi-Reihen", f.filmSeries.filter { it.crime }, "Reihen")?.let(::add)
        seriesRow("k-serien", "Krimiserien", f.series.filter { it.crime })?.let(::add)
        addAll(channelRows("k", f.crime.filter { !it.topic.startsWith("Tatort") && !it.topic.startsWith("Polizeiruf") }, "Krimis", 6))
    }

    Tab.DOKUS -> buildList {
        videoRow("d-new", "Neue Dokus", f.docs, "Dokus", chronological = true)?.let(::add)
        seriesRow("d-reihen", "Doku-Reihen", f.docSeries, "Reihen")?.let(::add)
        for ((theme, re) in docThemes) {
            val list = f.docs.filter { re.containsMatchIn(it.topic) || re.containsMatchIn(it.title) }
            if (list.size >= 6) videoRow("d-$theme", theme, list, "Dokus")?.let(::add)
        }
        addAll(channelRows("d", f.docs, "Dokus"))
    }

    else -> emptyList()
}

/** Persönliche Reihen (Weiterschauen, Meine Liste) – ändern sich laufend, darum separat. */
@Composable
fun personalShelves(tab: Tab): List<Shelf> {
    val resume = Library.continueWatching()
    val list = Library.list
    fun resumeShelf() = resume.takeIf { it.isNotEmpty() }?.let { r ->
        Shelf("resume", "Weiterschauen", r.map { p ->
            VideoTile(p.video,
                overline = listOfNotNull(p.seriesName, p.video.episode?.label).joinToString(" · ").ifBlank { overlineOf(p.video) },
                title = p.seriesName?.let { Meta.episodeTitle(p.video.title, it) } ?: p.video.title,
                seriesName = p.seriesName, seriesId = p.seriesId, saved = true)
        })
    }
    fun listShelf() = list.takeIf { it.isNotEmpty() }?.let { l ->
        Shelf("mylist", "Meine Liste", l.mapNotNull { e ->
            e.series?.let { SeriesTile(it) } ?: e.video?.let { VideoTile(it, overlineOf(it), saved = true) }
        }, l.size)
    }
    return when (tab) {
        Tab.START -> listOfNotNull(resumeShelf())
        Tab.LISTE -> listOfNotNull(resumeShelf(), listShelf())
        else -> emptyList()
    }
}

// ============================================================================
// Seite mit Vorschau + Reihen
// ============================================================================

@Composable
fun ShelfPage(app: AppState, tab: Tab, shelves: List<Shelf>, nav: Nav, header: (@Composable () -> Unit)? = null, empty: String? = null) {
    val ui = app.pages.getValue(tab)
    val firstKey = shelves.firstOrNull()?.let { s -> s.tiles.firstOrNull()?.let { "${s.key}|${it.key}" } }
    // Vorschau: fokussierte Kachel, sonst die erste der Seite
    val shown = ui.preview ?: shelves.firstOrNull()?.tiles?.firstOrNull()?.preview(app.country)
    LaunchedEffect(firstKey) { if (ui.focusKey == null) ui.focusKey = firstKey }

    Column(Modifier.fillMaxSize()) {
        header?.invoke()
        Hero(shown, height = if (header != null) 150.dp else 190.dp)
        if (shelves.isEmpty()) {
            empty?.let { Hint(it, Modifier.padding(start = PAGE_PADDING, top = 12.dp)) }
            return@Column
        }
        LazyColumn(
            state = ui.column,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(shelves, key = { it.key }) { shelf -> ShelfRow(app, ui, shelf, nav) }
        }
    }
}

@Composable
private fun ShelfRow(app: AppState, ui: PageUi, shelf: Shelf, nav: Nav) {
    Column {
        Row(Modifier.padding(start = PAGE_PADDING, bottom = 8.dp)) {
            Text(shelf.title, color = TvColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            shelf.count?.let { Text("  $it", color = TvColors.TextMuted, fontSize = 15.sp) }
        }
        LazyRow(
            state = ui.row(shelf.key),
            contentPadding = PaddingValues(horizontal = PAGE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.tiles, key = { it.key }) { tile ->
                val key = "${shelf.key}|${tile.key}"
                TileView(
                    tile, app,
                    Modifier.width(CARD_WIDTH).restoreFocus(key, ui.focusKey, enabled = app.restorePending,
                        onRestored = { app.restorePending = false }) {
                        ui.focusKey = key
                        ui.preview = tile.preview(app.country) ?: ui.preview
                    },
                ) { nav.open(tile) }
            }
        }
    }
}

// ============================================================================
// Live-TV: alle Sender als Raster
// ============================================================================

@Composable
fun LivePage(app: AppState, nav: Nav) {
    val live = app.filtered?.live.orEmpty()
    val ui = app.pages.getValue(Tab.LIVE)
    LaunchedEffect(live.size) { if (ui.focusKey == null) ui.focusKey = live.firstOrNull()?.let { "live|${it.name}" } }
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING)) {
        Spacer(Modifier.height(8.dp))
        Text("Live-TV", color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("${live.size} Sender", color = TvColors.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CARD_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 6.dp),
        ) {
            gridItems(live, key = { it.name }) { ch ->
                val key = "live|${ch.name}"
                LiveCard(ch, Modifier.restoreFocus(key, ui.focusKey, enabled = app.restorePending,
                    onRestored = { app.restorePending = false }) { ui.focusKey = it }) { nav.open(LiveTile(ch)) }
            }
        }
    }
}

// ============================================================================
// Suche
// ============================================================================

@Composable
fun SearchPage(app: AppState, nav: Nav, onSearch: () -> Unit) {
    val s = app.search
    val shelves = buildList {
        if (s != null) {
            if (s.series.isNotEmpty()) add(Shelf("q-series", "Serien & Reihen", s.series.map { SeriesTile(it) }, s.series.size))
            if (s.videos.isNotEmpty()) add(Shelf("q-videos", "Sendungen", s.videos.map { VideoTile(it, overlineOf(it)) }, s.videos.size))
            s.live?.takeIf { it.isNotEmpty() }?.let { add(Shelf("q-live", "Weitere Treffer in allen Mediatheken", it.map { v -> VideoTile(v, overlineOf(v)) }, it.size)) }
        }
    }
    ShelfPage(app, Tab.SUCHE, shelves, nav,
        header = {
            Row(Modifier.padding(start = PAGE_PADDING, end = PAGE_PADDING, top = 8.dp, bottom = 4.dp)) {
                SearchField(app.query, Modifier.weight(1f), onChange = { app.query = it; if (it.isBlank()) app.search = null }, onSearch = onSearch)
                Spacer(Modifier.width(12.dp))
                VoiceButton { spoken -> app.query = spoken; onSearch() }
            }
        },
        empty = when {
            s == null -> "Titel, Serie oder Sendung eingeben – oder „Sprechen“ wählen."
            s.live == null -> "Suche läuft …"
            else -> "Keine Treffer für „${app.query}“."
        },
    )
}

@Composable
fun SearchField(value: String, modifier: Modifier, onChange: (String) -> Unit, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) Text("Filme, Serien, Sendungen suchen …", color = TvColors.TextMuted, fontSize = 16.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = TvColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(TvColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

// ============================================================================
// Einstellungen
// ============================================================================

@Composable
fun SettingsPage(app: AppState, onCheckUpdate: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section("Land", "Bestimmt, welche Sendungen angezeigt werden. Ob etwas abspielbar ist, entscheidet aber dein " +
            "Internetanschluss – erkannt: ${app.detected?.let { Geo.name(it) } ?: "–"}. Empfohlen: Automatisch.") {
            Chip("Automatisch", app.chosen == null) { app.setCountry(null) }
            Geo.countries.forEach { c -> Chip(Geo.name(c), app.chosen == c) { app.setCountry(c) } }
        }
        Section("App", "Installiert: Version ${BuildConfig.VERSION_NAME}. Updates werden automatisch angeboten.") {
            Chip(app.updateStatus ?: "Nach Updates suchen", false) { if (app.updateStatus == null) onCheckUpdate() }
        }
        val idx = app.index
        Section("Katalog", if (idx == null) "Wird geladen …" else
            "Stand ${dateOf(idx.generated)} · ${idx.films.size} Filme, ${idx.series.size} Serien, ${idx.crime.size} Krimis, " +
                "${idx.docs.size} Dokus, ${idx.live.size} Live-Sender. Aktualisiert sich automatisch alle 3 Stunden.") {}
        Section("Meine Daten", "Weiterschauen: ${Library.progress.size} · Meine Liste: ${Library.list.size} · Gesehen: ${Library.watched.size}") {}
    }
}

@Composable
private fun Section(title: String, text: String, content: @Composable RowScope.() -> Unit) = Column {
    Text(title, color = TvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(text, color = TvColors.TextMuted, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
