@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Maße: ca. 5 Kacheln pro Zeile auf einem 16:9-Fernseher
val CARD_WIDTH = 180.dp
val CARD_IMAGE = 101.dp          // 16:9
val PAGE_PADDING = 28.dp
val RAIL_WIDTH = 76.dp           // eingeklappte Seitenleiste

// ============================================================================
// Kacheln
// ============================================================================

/** Rendert eine beliebige Kachel einer Reihe. */
@Composable
fun TileView(tile: Tile, app: AppState, modifier: Modifier, onClick: () -> Unit) = when (tile) {
    is VideoTile -> VideoCard(tile.video, tile.overline, tile.title, app.sinceVisit, modifier, onClick)
    is SeriesTile -> SeriesCard(tile.series, app.country, app.sinceVisit, modifier, onClick)
    is LiveTile -> LiveCard(tile.channel, app.playableHere(tile.channel), modifier, Epg.now(tile.channel.name, app.clock), onClick)
    is AllTile -> AllCard(tile.count, tile.label, modifier, onClick)
}

@Composable
fun VideoCard(video: Video, overline: String, title: String, newSince: Long, modifier: Modifier, onClick: () -> Unit) {
    val watched = Library.isWatched(video)
    val progress = Library.progressOf(video)?.takeIf { it.position > 0 }?.fraction
    val isNew = video.firstSeen > 0 && video.firstSeen > newSince && !watched
    TileCard(
        channel = video.channel, image = video.image, overline = overline, title = title, modifier = modifier,
        badge = when { watched -> "✓"; isNew -> "NEU"; else -> null },
        badgeColor = if (watched) TvColors.SurfaceHigh else TvColors.New,
        progress = progress, onClick = onClick,
    )
}

@Composable
fun SeriesCard(s: SeriesInfo, country: String, newSince: Long, modifier: Modifier, onClick: () -> Unit) {
    val films = s.kind == SeriesKind.FILM_SERIES
    val isNew = s.newestEpisode > 0 && s.newestEpisode > newSince
    TileCard(
        channel = s.channel, image = s.image,
        overline = "${s.countIn(country)} " + (if (films) "Filme" else "Folgen") + (if (s.crime) " · Krimi" else ""),
        title = s.name, modifier = modifier,
        badge = if (isNew) (if (films) "NEUER FILM" else "NEUE FOLGEN") else null,
        badgeColor = TvColors.New, onClick = onClick,
    )
}

@Composable
fun LiveCard(ch: LiveChannel, playable: Boolean, modifier: Modifier, now: Program? = null, onClick: () -> Unit) =
    TileCard(channel = ch.channel, image = null,
        overline = if (!playable) onlyIn(ch) else now?.let { "Jetzt: ${it.title}" } ?: "Jetzt live",
        title = ch.name, modifier = modifier, badge = if (playable) "LIVE" else "nur ${ch.homeCountry}",
        badgeColor = if (playable) TvColors.New else TvColors.SurfaceHigh, placeholder = ch.name,
        progress = now?.progress(nowSec()), onClick = onClick)

fun onlyIn(ch: LiveChannel) = "Nur in ${Geo.name(ch.homeCountry)} abspielbar"

@Composable
fun AllCard(total: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick, modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.Surface, focusedContainerColor = TvColors.Accent),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(Modifier.fillMaxWidth().height(CARD_IMAGE + 44.dp).padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text("Alle anzeigen →", color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("$total $label", color = TvColors.Text.copy(alpha = 0.75f), fontSize = 12.sp)
        }
    }
}

/**
 * Grundkachel: 16:9-Bild (oder Senderfarbe mit großem Namen), darunter Kurzinfo und Titel.
 * Bewusst schlicht – Details zeigt die Vorschau oben.
 */
@Composable
fun TileCard(
    channel: String,
    image: String?,
    overline: String,
    title: String,
    modifier: Modifier,
    badge: String? = null,
    badgeColor: Color = TvColors.New,
    progress: Float? = null,
    placeholder: String = displayChannel(channel),
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick, modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(CARD_IMAGE).clip(shape)
                    .background(Brush.linearGradient(listOf(TvColors.channel(channel), TvColors.channel(channel).copy(alpha = 0.55f))))
            ) {
                if (image != null) Picture(image, Modifier.fillMaxSize())
                else Text(placeholder, color = Color.White.copy(alpha = 0.92f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp))
                badge?.let {
                    Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                            .background(badgeColor).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                progress?.let { f ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f.coerceAtLeast(0.03f)).height(4.dp).background(TvColors.Accent))
                }
            }
            Column(Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
                Text(title, color = TvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(overline, color = TvColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Bild aus dem Netz; bis es geladen ist, bleibt der Hintergrund (Senderfarbe) sichtbar. */
@Composable
fun Picture(url: String, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(150).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** Größere Fassung desselben Bildes für Vorschau und Details. */
fun largeImage(url: String) = url.replace("w=480", "w=960").replace("~384x216", "~768x432")
    .replace("layout=384x216", "layout=768x432").replace("/480x270", "/960x540").replace("width=480", "width=960")

// ============================================================================
// Vorschau oben (Bühne)
// ============================================================================

@Composable
fun Hero(preview: Preview?, height: androidx.compose.ui.unit.Dp = 190.dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        preview?.image?.let { img ->
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.52f).fillMaxHeight()) {
                Picture(largeImage(img), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to TvColors.Background, 0.45f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to TvColors.Background)))
            }
        }
        preview?.let { p ->
            Column(Modifier.fillMaxWidth(0.58f).padding(start = PAGE_PADDING, top = 6.dp)) {
                Text(p.overline.uppercase(), color = TvColors.Accent, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(p.title, color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 30.sp)
                Spacer(Modifier.height(4.dp))
                Text(p.meta, color = TvColors.TextMuted, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(p.description, color = TvColors.Text.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            }
        }
    }
}

fun Tile.preview(country: String): Preview? = when (this) {
    is VideoTile -> Preview(seriesName ?: video.topic, title, metaLine(video), video.description, video.image)
    is SeriesTile -> Preview(
        "${kindLabel(series.kind)} · ${displayChannel(series.channel)}", series.name,
        "${series.countIn(country)} " + (if (series.kind == SeriesKind.FILM_SERIES) "Filme" else "Folgen"),
        series.description, series.image,
    )
    is LiveTile -> Epg.now(channel.name).let { p ->
        Preview("TV-Sender · ${channel.name}", p?.title ?: channel.name,
            p?.let { "Jetzt ${it.times}" + (if (it.subtitle.isNotBlank()) " · ${it.subtitle}" else "") } ?: "Jetzt live",
            p?.description.orEmpty(), null)
    }
    is AllTile -> null
}

// ============================================================================
// Bedienelemente
// ============================================================================

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) TvColors.Accent else TvColors.SurfaceHigh,
            contentColor = TvColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = TvColors.Background,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
fun Info(label: String, value: String) = Column {
    Text(label, color = TvColors.TextMuted, fontSize = 12.sp)
    Text(value, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier.padding(start = PAGE_PADDING)) =
    Text(text, color = TvColors.TextMuted, fontSize = 14.sp, modifier = modifier)

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.padding(PAGE_PADDING)) {
        Text(message, color = TvColors.Text, fontSize = 17.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Erneut versuchen") }
    }
}

/**
 * Merkt sich die fokussierte Kachel und holt den Fokus zurück, wenn [enabled] –
 * sonst "zeigt" die Fernbedienung nach dem Zurückkehren ins Leere.
 */
@Composable
fun Modifier.restoreFocus(key: String, target: () -> String?, tick: Int = 0, onFocused: (String) -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    // Beim Erscheinen und bei jeder Anforderung ([tick]) prüfen; der gemerkte Schlüssel wird bewusst nicht
    // in der Komposition gelesen – sonst baute jede Fokus-Bewegung alle sichtbaren Kacheln neu auf (träge).
    LaunchedEffect(tick) {
        withFrameNanos { }
        if (key == target()) runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused(key) }
}

@Composable
fun UpdateDialog(update: Update, onDismiss: () -> Unit) {
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

fun kindLabel(k: SeriesKind) = when (k) {
    SeriesKind.SERIES -> "Serie"; SeriesKind.FILM_SERIES -> "Film-Reihe"; SeriesKind.DOC_SERIES -> "Doku-Reihe"
}

/** Lesbare Sendernamen ("3Sat" -> "3sat", "ARTE.DE" -> "ARTE"). */
fun displayChannel(ch: String) = when (ch) {
    "3Sat" -> "3sat"; "ARTE.DE" -> "ARTE"; "PHOENIX" -> "phoenix"; "ARD" -> "Das Erste"; else -> ch
}

/** Bei Reihen (Tatort …) der Reihenname, bei Sammel-Rubriken Genre und Jahr. */
fun overlineOf(v: Video): String =
    (if (Meta.isRubric(v.topic)) listOfNotNull(v.genre, v.year?.toString(), displayChannel(v.channel))
     else listOfNotNull(v.topic, v.year?.toString()))
        .joinToString(" · ").ifBlank { v.topic }

fun metaLine(v: Video): String = buildList {
    v.genre?.let { add(it) }
    v.year?.let { add(it.toString()) }
    add("${v.minutes} Min")
    add(displayChannel(v.channel))
    if (v.hasSubtitles) add("Untertitel")
    if (v.variants.isNotEmpty()) add("${v.versions.size} Fassungen")
}.joinToString("  ·  ")

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun dateOf(ts: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(ts * 1000))
