package com.example.mediathektv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

/** Navigationszustand. */
private sealed class Screen {
    object Home : Screen()
    data class Episodes(val series: Series) : Screen()
    data class Detail(val video: Video) : Screen()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                onOpenVideo = { screen = Screen.Detail(it) },
                onOpenSeries = { screen = Screen.Episodes(it) }
            )
            is Screen.Episodes -> EpisodesScreen(s.series,
                onOpen = { screen = Screen.Detail(it) },
                onBack = { screen = Screen.Home })
            is Screen.Detail -> DetailScreen(s.video,
                onPlay = { v, sub -> PlayerActivity.start(ctx, v, sub) },
                onBack = { screen = Screen.Home })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeScreen(onOpenVideo: (Video) -> Unit, onOpenSeries: (Series) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<List<Video>?>(null) }
    var films by remember { mutableStateOf<List<Video>>(emptyList()) }
    var series by remember { mutableStateOf<List<Series>>(emptyList()) }
    var crime by remember { mutableStateOf<List<Video>>(emptyList()) }
    var docs by remember { mutableStateOf<List<Video>>(emptyList()) }
    var selected by remember { mutableStateOf<Video?>(null) }
    var update by remember { mutableStateOf<Update?>(null) }
    var installing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        launch { runCatching { Api.films() }.onSuccess { films = it } }
        launch { runCatching { Api.series() }.onSuccess { series = it } }
        launch { runCatching { Api.crime() }.onSuccess { crime = it } }
        launch { runCatching { Api.docs() }.onSuccess { docs = it } }
        launch { update = Updater.check() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Mediathek", color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(360.dp).clip(RoundedCornerShape(24.dp)).background(TvColors.Surface)
                .padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (query.isEmpty()) Text("Suchen …", color = TvColors.TextMuted, fontSize = 18.sp)
                BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = TvColors.Text, fontSize = 18.sp),
                    cursorBrush = SolidColor(TvColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        scope.launch {
                            search = if (query.isBlank()) null
                            else runCatching { Api.query(text = query, size = 50) }.getOrDefault(emptyList())
                        }
                    }))
            }
        }
        // Vorschauzeile
        Box(Modifier.height(74.dp).padding(horizontal = 56.dp)) {
            selected?.let { v ->
                Column {
                    Text(v.topic.uppercase(), color = TvColors.Accent, fontSize = 13.sp, letterSpacing = 1.sp)
                    Text(v.title, color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(metaLine(v), color = TvColors.TextMuted, fontSize = 14.sp)
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)) {
            search?.let { item { VideoRow("Suche: „$query“", it, { selected = it }, onOpenVideo) } }
            if (films.isNotEmpty()) item { VideoRow("Filme", films, { selected = it }, onOpenVideo) }
            if (series.isNotEmpty()) item { SeriesRow("Serien", series, onOpenSeries) }
            if (crime.isNotEmpty()) item { VideoRow("Krimi", crime, { selected = it }, onOpenVideo) }
            if (docs.isNotEmpty()) item { VideoRow("Dokumentationen", docs, { selected = it }, onOpenVideo) }
        }
    }

    update?.let { u ->
        UpdateDialog(u, installing,
            onInstall = { installing = true; scope.launch { Updater.downloadAndInstall(ctx, u); installing = false } },
            onLater = { update = null })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesScreen(series: Series, onOpen: (Video) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var eps by remember { mutableStateOf(series.episodes) }
    LaunchedEffect(series.topic) { runCatching { Api.episodes(series.topic) }.onSuccess { if (it.isNotEmpty()) eps = it } }
    Column(Modifier.fillMaxSize().padding(56.dp)) {
        Text(series.topic, color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("${series.channel}  ·  ${eps.size} Folgen", color = TvColors.TextMuted, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(eps, key = { it.id }) { v -> VideoCard(v, {}, onOpen) }
        }
    }
    BackHandlerKey(onBack)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailScreen(video: Video, onPlay: (Video, Boolean) -> Unit, onBack: () -> Unit) {
    var lang by remember { mutableStateOf(video) }   // gewählte Sprachfassung
    val langs = listOf(video) + video.variants
    Column(Modifier.fillMaxSize().padding(56.dp)) {
        Text(video.topic.uppercase(), color = TvColors.Accent, fontSize = 14.sp, letterSpacing = 1.sp)
        Text(video.title, color = TvColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(metaLine(video), color = TvColors.TextMuted, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Text(video.description, color = TvColors.Text.copy(alpha = 0.85f), fontSize = 16.sp,
            maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = { onPlay(lang, lang.hasSubtitles) }) {
                Text("▶  Abspielen" + if (lang.hasSubtitles) " (mit UT)" else "", fontSize = 18.sp)
            }
        }
        if (langs.size > 1) {
            Spacer(Modifier.height(22.dp))
            Text("Sprachfassung", color = TvColors.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                langs.forEach { l ->
                    Button(onClick = { lang = l },
                        colors = ButtonDefaults.colors(
                            containerColor = if (l.id == lang.id) TvColors.Accent else TvColors.SurfaceHigh)) {
                        Text(l.language, fontSize = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            InfoChip("Rubrik", video.topic)
            video.productionYear?.let { InfoChip("Jahr", it.toString()) }
            InfoChip("Untertitel", if (video.hasSubtitles) "verfügbar" else "–")
            InfoChip("Dauer", "${video.duration / 60} Min")
        }
    }
    BackHandlerKey(onBack)
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(label, color = TvColors.TextMuted, fontSize = 12.sp)
        Text(value, color = TvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoRow(label: String, videos: List<Video>, onFocus: (Video) -> Unit, onClick: (Video) -> Unit) {
    Column {
        Text(label, color = TvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(videos, key = { it.id }) { v -> VideoCard(v, onFocus, onClick) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeriesRow(label: String, series: List<Series>, onClick: (Series) -> Unit) {
    Column {
        Text(label, color = TvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(series, key = { it.topic }) { s ->
                Card(onClick = { onClick(s) }, modifier = Modifier.width(300.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = CardDefaults.colors(TvColors.Surface, focusedContainerColor = TvColors.SurfaceHigh),
                    scale = CardDefaults.scale(focusedScale = 1.08f)) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(150.dp)
                            .background(Brush.linearGradient(listOf(channelColor(s.channel), TvColors.Surface)))) {
                            Text(s.channel, color = Color.White.copy(0.9f), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                            Text("${s.episodes.size} Folgen", color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.55f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text(s.topic, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp).height(42.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoCard(v: Video, onFocus: (Video) -> Unit, onClick: (Video) -> Unit) {
    Card(onClick = { onClick(v) },
        modifier = Modifier.width(300.dp).onFocusChanged { if (it.isFocused) onFocus(v) },
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(TvColors.Surface, focusedContainerColor = TvColors.SurfaceHigh),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(focusedBorder = Border(
            androidx.compose.foundation.BorderStroke(3.dp, TvColors.Accent), shape = RoundedCornerShape(14.dp)))) {
        Column {
            Box(Modifier.fillMaxWidth().height(150.dp)
                .background(Brush.linearGradient(listOf(channelColor(v.channel), TvColors.Surface)))) {
                Text(v.channel, color = Color.White.copy(0.9f), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (v.hasSubtitles) Badge("UT")
                    if (v.variants.isNotEmpty()) Badge("Sprachen")
                    Badge("${v.duration / 60} Min")
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(v.topic, color = TvColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v.title, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Zurück-Taste der Fernbedienung abfangen. */
@Composable
private fun BackHandlerKey(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}

private fun metaLine(v: Video): String {
    val parts = mutableListOf(v.channel, "${v.duration / 60} Min", dateOf(v.timestamp))
    v.productionYear?.let { parts.add(1, it.toString()) }
    if (v.hasSubtitles) parts.add("Untertitel")
    if (v.variants.isNotEmpty()) parts.add("${v.variants.size + 1} Sprachen")
    return parts.joinToString("  ·  ")
}

private fun channelColor(ch: String): Color = when {
    ch.startsWith("ARD") || ch == "Das Erste" -> Color(0xFF0D3B8C)
    ch.startsWith("ZDF") -> Color(0xFFE8650B)
    ch == "ARTE.DE" -> Color(0xFFB0271E)
    ch == "3Sat" -> Color(0xFF8E2A6B)
    ch == "PHOENIX" -> Color(0xFF1E6F5C)
    ch == "SRF" -> Color(0xFF8C0A0A)
    else -> Color(0xFF2B5CA8)
}
private fun dateOf(ts: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ts * 1000))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdateDialog(update: Update, installing: Boolean, onInstall: () -> Unit, onLater: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(TvColors.Surface).padding(36.dp)) {
            Text("Update verfügbar", color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Version ${update.versionName} ist da (installiert: ${BuildConfig.VERSION_NAME}).",
                color = TvColors.TextMuted, fontSize = 17.sp)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = onInstall, enabled = !installing) {
                    Text(if (installing) "Wird geladen …" else "Jetzt installieren", fontSize = 17.sp)
                }
                Button(onClick = onLater, enabled = !installing,
                    colors = ButtonDefaults.colors(containerColor = TvColors.SurfaceHigh)) {
                    Text("Später", fontSize = 17.sp)
                }
            }
        }
    }
}
