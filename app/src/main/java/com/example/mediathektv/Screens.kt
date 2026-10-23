@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@Composable
fun FilterRow(content: LazyListScope.() -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

// ============================================================================
// Alle anzeigen: Raster mit Filtern
// ============================================================================

@Composable
fun BrowseScreen(app: AppState, state: BrowseState, nav: Nav) {
    val spec = state.spec
    val channels = remember(spec) {
        (if (spec.isSeries) spec.series.map { groupOf(it.channel) } else spec.videos.map { groupOf(it.channel) })
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val genres = remember(spec) {
        if (spec.isSeries) emptyList()
        else spec.videos.mapNotNull { it.genre }.groupingBy { it }.eachCount().filter { it.value >= 5 }
            .entries.sortedByDescending { it.value }.map { it.key }.take(10)
    }
    val videos = remember(spec, state.sort, state.channel, state.genre) {
        spec.videos.filter { (state.channel == null || groupOf(it.channel) == state.channel) && (state.genre == null || it.genre == state.genre) }
            .let { l -> if (state.sort == Sort.AZ) l.sortedBy { it.title.lowercase() } else l }
    }
    val series = remember(spec, state.sort, state.channel) {
        spec.series.filter { state.channel == null || groupOf(it.channel) == state.channel }
            .let { l -> if (state.sort == Sort.AZ) l.sortedBy { it.name.lowercase() } else l }
    }
    val count = if (spec.isSeries) series.size else videos.size
    LaunchedEffect(state.focusKey == null, count) {
        if (state.focusKey == null) state.focusKey = series.firstOrNull()?.id ?: videos.firstOrNull()?.id
    }

    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING, vertical = 24.dp)) {
        Text(spec.title, color = TvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("$count ${spec.unit}", color = TvColors.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        FilterRow {
            Sort.entries.forEach { s -> item { Chip(s.label, state.sort == s) { state.sort = s } } }
            item { Spacer(Modifier.width(16.dp)) }
            if (channels.size > 1) {
                item { Chip("Alle Sender", state.channel == null) { state.channel = null } }
                items(channels) { ch -> Chip(ch, state.channel == ch) { state.channel = if (state.channel == ch) null else ch } }
            }
        }
        if (genres.size > 1) {
            Spacer(Modifier.height(8.dp))
            FilterRow {
                item { Chip("Alle Genres", state.genre == null) { state.genre = null } }
                items(genres) { g -> Chip(g, state.genre == g) { state.genre = if (state.genre == g) null else g } }
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CARD_WIDTH),
            state = state.grid,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (spec.isSeries) gridItems(series, key = { it.id }) { s ->
                SeriesCard(s, app.country, app.sinceVisit, Modifier.restoreFocus(s.id, state.focusKey) { state.focusKey = it }) { nav.open(SeriesTile(s)) }
            } else gridItems(videos, key = { it.id }) { v ->
                VideoCard(v, overlineOf(v), v.title, app.sinceVisit, Modifier.restoreFocus(v.id, state.focusKey) { state.focusKey = it }) {
                    nav.open(VideoTile(v, overlineOf(v)))
                }
            }
        }
    }
}

// ============================================================================
// Serie / Reihe: Folgen
// ============================================================================

@Composable
fun SeriesScreen(app: AppState, state: SeriesState, onVideo: (Video, List<Video>) -> Unit) {
    val country = app.country
    val context = LocalContext.current
    LaunchedEffect(state, country) { if (state.episodes == null) state.load(app.repo, country) }
    val s = state.info
    val unit = if (s.kind == SeriesKind.FILM_SERIES) "Filme" else "Folgen"
    val episodic = s.kind != SeriesKind.FILM_SERIES
    val ordered = state.episodes.orEmpty()

    Box(Modifier.fillMaxSize()) {
        s.image?.let { Backdrop(it, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.5f).height(260.dp)) }
        Column(Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING, vertical = 24.dp)) {
            Text("${kindLabel(s.kind)} · ${displayChannel(s.channel)}".uppercase(), color = TvColors.Accent, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(s.name, color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.6f))
            Text(state.episodes?.let { "${it.size} $unit" } ?: "$unit werden geladen …", color = TvColors.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(s.description, color = TvColors.Text.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.55f))
            Spacer(Modifier.height(12.dp))

            val nextUp = ordered.firstOrNull { Library.progressOf(it)?.let { p -> p.position > 0 } == true }
                ?: ordered.firstOrNull { !Library.isWatched(it) }
            FilterRow {
                if (nextUp != null && episodic) item {
                    Chip("▶  ${nextUp.episode?.label ?: "Nächste Folge"} abspielen", selected = true) {
                        PlayerActivity.play(context, nextUp, false, queueAfter(ordered, nextUp), s.id, s.name)
                    }
                }
                item {
                    val inList = Library.inList(Library.seriesKey(s))
                    Chip(if (inList) "✓  In meiner Liste" else "+  Meine Liste", inList) { Library.toggle(s) }
                }
                if (state.seasons.size > 1) {
                    item { Spacer(Modifier.width(16.dp)) }
                    item { Chip("Alle Staffeln", state.season == null) { state.season = null } }
                    items(state.seasons) { n -> Chip("Staffel $n", state.season == n) { state.season = n } }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (state.episodes?.isEmpty() == true) Hint("Keine $unit verfügbar.", Modifier)

            LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                state = state.grid,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                gridItems(state.visible.distinctBy { it.id }, key = { it.id }) { v ->
                    VideoCard(
                        video = v,
                        overline = v.episode?.label ?: listOfNotNull(v.year?.toString(), dateOf(v.timestamp)).first(),
                        title = Meta.episodeTitle(v.title, s.name),
                        newSince = app.sinceVisit,
                        modifier = Modifier.restoreFocus(v.id, state.focusKey) { state.focusKey = it },
                    ) { onVideo(v, if (episodic) queueAfter(ordered, v) else emptyList()) }
                }
            }
        }
    }
}

// ============================================================================
// Detailansicht
// ============================================================================

@Composable
fun DetailScreen(app: AppState, video: Video, seriesName: String?, seriesId: String?, initialQueue: List<Video>?) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(video) }
    var subtitles by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(initialQueue) }
    LaunchedEffect(seriesId) {   // aus "Weiterschauen" geöffnet: folgende Folgen aus der Serie nachladen
        if (queue == null && seriesId != null && video.episode != null) {
            val eps = app.repo.episodes(seriesId).mapNotNull { it.forCountry(app.country)?.withCover(video.image) }
            queue = queueAfter(eps, eps.firstOrNull { it.stableKey == video.stableKey } ?: video)
        }
    }
    val progress = Library.progressOf(video)?.takeIf { it.position > 0 }
    val watched = Library.isWatched(video)
    fun play(fromStart: Boolean) = PlayerActivity.play(
        context, version, subtitles && version.hasSubtitles, queue.orEmpty(), seriesId, seriesName, fromStart)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(
        Brush.horizontalGradient(listOf(TvColors.channel(video.channel).copy(alpha = 0.3f), TvColors.Background)))) {
        video.image?.let { Backdrop(it, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.6f).fillMaxHeight(0.8f)) }
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Text((seriesName ?: video.topic).uppercase(), color = TvColors.Accent, fontSize = 13.sp, letterSpacing = 1.sp)
            Text(seriesName?.let { Meta.episodeTitle(video.title, it) } ?: video.title,
                color = TvColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                video.episode?.let { Info("Folge", it.label) }
                video.genre?.let { Info("Genre", it) }
                video.year?.let { Info("Jahr", it.toString()) }
                Info("Dauer", "${video.minutes} Min")
                Info("Sender", displayChannel(video.channel))
                Info("Ausgestrahlt", dateOf(video.timestamp))
            }
            Spacer(Modifier.height(16.dp))
            Text(video.description, color = TvColors.Text.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { play(fromStart = false) }, modifier = Modifier.focusRequester(playFocus),
                    colors = ButtonDefaults.colors(focusedContainerColor = Color.White, focusedContentColor = TvColors.Background)) {
                    Text(if (progress != null) "▶  Fortsetzen (${clock(progress.position)})" else "▶  Abspielen", fontSize = 17.sp)
                }
                if (progress != null) Chip("↺  Von vorne", false) { play(fromStart = true) }
                if (version.hasSubtitles) Chip(if (subtitles) "Untertitel: An" else "Untertitel: Aus", subtitles) { subtitles = !subtitles }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val inList = Library.inList(video.stableKey)
                Chip(if (inList) "✓  In meiner Liste" else "+  Meine Liste", inList) { Library.toggle(video) }
                Chip(if (watched) "✓  Gesehen" else "Als gesehen markieren", watched) { Library.markWatched(video, !watched) }
                if (progress != null) Chip("Aus „Weiterschauen“ entfernen", false) { Library.removeProgress(video) }
            }
            if (video.versions.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text("Sprache / Fassung", color = TvColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    video.versions.forEach { v -> Chip(v.language, v.id == version.id) { version = v } }
                }
            }
            queue?.firstOrNull()?.let { next ->
                Spacer(Modifier.height(14.dp))
                Text("Danach automatisch: ${next.episode?.label?.let { "$it · " }.orEmpty()}${seriesName?.let { Meta.episodeTitle(next.title, it) } ?: next.title}",
                    color = TvColors.TextMuted, fontSize = 13.sp)
            }
            if (!video.geoKnown) {
                Spacer(Modifier.height(12.dp))
                Text("Hinweis: Treffer aus der Live-Suche – die Ländersperre ist unbekannt.", color = TvColors.TextMuted, fontSize = 13.sp)
            }
        }
    }
}

/** Großes Hintergrundbild, nach links und unten in den Hintergrund auslaufend. */
@Composable
fun Backdrop(image: String, modifier: Modifier) = Box(modifier) {
    Picture(largeImage(image), Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to TvColors.Background, 0.5f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to TvColors.Background)))
}

// ============================================================================
// Spracheingabe
// ============================================================================

/** Spracheingabe für die Suche – nur sichtbar, wenn das Gerät Spracherkennung anbietet. */
@Composable
fun VoiceButton(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val available = remember {
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    if (!available) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onResult)
    }
    Chip("🎤  Sprechen", selected = false) {
        launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Was möchtest du sehen?"))
    }
}
