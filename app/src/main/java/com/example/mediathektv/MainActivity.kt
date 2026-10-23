package com.example.mediathektv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
        setContent { HomeScreen { v -> PlayerActivity.start(this, v) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(onPlay: (Video) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Video>?>(null) }
    var rows by remember { mutableStateOf<Map<String, List<Video>>>(emptyMap()) }
    var selected by remember { mutableStateOf<Video?>(null) }
    var update by remember { mutableStateOf<Update?>(null) }
    var installing by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { update = Updater.check() }

    LaunchedEffect(Unit) {
        HOME_ROWS.forEach { (label, channels) ->
            launch {
                runCatching { Api.query(channels = channels, size = 20) }
                    .onSuccess { list -> rows = rows + (label to list) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            // Kopfbereich: Titel + Suche + Vorschau der markierten Sendung
            Header(
                selected = selected,
                query = query,
                onQueryChange = { query = it },
                onSearch = {
                    scope.launch {
                        searchResults = if (query.isBlank()) null
                        else runCatching { Api.query(text = query, size = 40) }.getOrDefault(emptyList())
                    }
                }
            )

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                searchResults?.let { results ->
                    item {
                        VideoRow("Suche: „$query“", results, onFocus = { selected = it }, onClick = onPlay)
                    }
                }
                HOME_ROWS.forEach { (label, _) ->
                    val list = rows[label] ?: return@forEach
                    item { VideoRow(label, list, onFocus = { selected = it }, onClick = onPlay) }
                }
            }
        }

        update?.let { u ->
            UpdateDialog(
                update = u,
                installing = installing,
                onInstall = {
                    installing = true
                    scope.launch { Updater.downloadAndInstall(context, u); installing = false }
                },
                onLater = { update = null }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdateDialog(update: Update, installing: Boolean, onInstall: () -> Unit, onLater: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(TvColors.Surface).padding(36.dp)
        ) {
            Text("Update verfügbar", color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Version ${update.versionName} ist da (installiert: ${BuildConfig.VERSION_NAME}).",
                color = TvColors.TextMuted, fontSize = 17.sp
            )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Header(
    selected: Video?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mediathek", color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // Suchfeld
            Box(
                Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvColors.Surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                if (query.isEmpty()) Text("Sendung suchen …", color = TvColors.TextMuted, fontSize = 18.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = TvColors.Text, fontSize = 18.sp),
                    cursorBrush = SolidColor(TvColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() })
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        // Vorschau der fokussierten Sendung
        Box(Modifier.height(110.dp)) {
            selected?.let { v ->
                Column {
                    Text(v.topic.uppercase(), color = TvColors.Accent, fontSize = 14.sp, letterSpacing = 1.sp)
                    Text(v.title, color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${v.channel}  ·  ${v.duration / 60} Min  ·  ${dateOf(v.timestamp)}",
                        color = TvColors.TextMuted, fontSize = 15.sp
                    )
                    Text(v.description, color = TvColors.TextMuted, fontSize = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoRow(
    label: String,
    videos: List<Video>,
    onFocus: (Video) -> Unit,
    onClick: (Video) -> Unit
) {
    Column {
        Text(label, color = TvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(videos, key = { it.id }) { v -> VideoCard(v, onFocus, onClick) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoCard(v: Video, onFocus: (Video) -> Unit, onClick: (Video) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { onClick(v) },
        modifier = Modifier
            .width(300.dp)
            .onFocusChanged { if (it.isFocused) { focused = true; onFocus(v) } else focused = false },
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(containerColor = TvColors.Surface, focusedContainerColor = TvColors.SurfaceHigh),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, TvColors.Accent), shape = RoundedCornerShape(14.dp))
        )
    ) {
        Column {
            // Platzhalter-Bild: Farbverlauf je Sender (MediathekViewWeb liefert keine Thumbnails)
            Box(
                Modifier.fillMaxWidth().height(150.dp)
                    .background(Brush.linearGradient(listOf(channelColor(v.channel), TvColors.Surface)))
            ) {
                Text(v.channel, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                Text("${v.duration / 60} Min", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Column(Modifier.padding(12.dp)) {
                Text(v.topic, color = TvColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v.title, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(42.dp))
            }
        }
    }
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

private fun dateOf(ts: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ts * 1000))

