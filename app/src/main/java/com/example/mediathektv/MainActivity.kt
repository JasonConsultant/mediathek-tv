@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.mediathektv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Incoming.handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Incoming.handle(intent)
        Library.init(applicationContext)
        CatalogRefreshWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TvColors.Accent,
                    surface = TvColors.Surface,
                    background = TvColors.Background,
                    onSurface = TvColors.Text,
                    inverseOnSurface = TvColors.Background,
                )
            ) { App() }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val app = remember { AppState(context.applicationContext) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { app.start() }

    // Solange die App sichtbar ist: bei Rückkehr und alle 15 Minuten nach Neuem schauen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!app.loading) app.sync()
                if (app.updateCheckDue()) Updater.check()?.let { app.update = it }
                delay(SYNC_TICK)
            }
        }
    }

    // App als Ganzes verlassen / zurückgekehrt (für "Neu seit deinem letzten Besuch" und den TV-Startbildschirm)
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    app.onAppLeft()
                    val ctx = context.applicationContext
                    Thread { WatchNext.sync(ctx) }.start()
                }
                Lifecycle.Event.ON_START -> app.onAppReturned()
                else -> Unit
            }
        }
        val process = ProcessLifecycleOwner.get().lifecycle
        process.addObserver(observer)
        onDispose { process.removeObserver(observer) }
    }

    // Klick auf "Weiterschauen" im TV-Startbildschirm: direkt fortsetzen
    LaunchedEffect(Incoming.playKey) {
        val key = Incoming.playKey ?: return@LaunchedEffect
        Incoming.playKey = null
        val p = Library.progress[key] ?: return@LaunchedEffect
        val queue = if (p.seriesId != null && p.video.episode != null) runCatching {
            val eps = app.repo.episodes(p.seriesId).mapNotNull { it.forCountry(app.country)?.withCover(p.video.image) }
            queueAfter(eps, eps.firstOrNull { it.stableKey == key } ?: p.video)
        }.getOrDefault(emptyList()) else emptyList()
        PlayerActivity.play(context, p.video, false, queue, p.seriesId, p.seriesName)
    }
    // Suchanfrage vom System
    LaunchedEffect(Incoming.query) {
        val q = Incoming.query ?: return@LaunchedEffect
        Incoming.query = null
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        app.tab = Tab.SUCHE
        app.query = q
        app.runSearch()
    }

    val nav = remember {
        object : Nav {
            override fun open(tile: Tile) {
                when (tile) {
                    is VideoTile -> {
                        // aus Weiterschauen/Merkliste: wenn möglich die aktuelle Fassung aus dem Katalog
                        val v = if (tile.saved) app.filtered?.let { f ->
                            (f.films + f.crime + f.docs).firstOrNull { it.stableKey == tile.video.stableKey }
                        } ?: tile.video else tile.video
                        stack.add(Screen.Detail(v, tile.seriesName, tile.seriesId))
                    }
                    is SeriesTile -> stack.add(Screen.SeriesView(SeriesState(tile.series)))
                    is LiveTile -> PlayerActivity.playLive(context, tile.channel)
                    is AllTile -> tile.spec?.let { browse(it) } ?: tile.tab?.let { tab(it); app.restorePending = true }
                }
            }
            override fun browse(spec: BrowseSpec) { stack.add(Screen.Browse(BrowseState(spec))) }
            override fun tab(tab: Tab) { app.tab = tab }
        }
    }

    // Zurück: Unterseite schließen → zur Startseite → App verlassen
    BackHandler(enabled = stack.size > 1 || app.tab != Tab.START) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else app.tab = Tab.START
        app.restorePending = true
    }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        when (val screen = stack.last()) {
            Screen.Main -> Column(Modifier.fillMaxSize()) {
                TopBar(app)
                MainContent(app, nav,
                    onSearch = { scope.launch { app.runSearch() } },
                    onRetry = { scope.launch { app.start() } },
                    onCheckUpdate = { scope.launch { app.checkUpdateNow() } })
            }
            is Screen.Browse -> BrowseScreen(app, screen.state, nav)
            is Screen.SeriesView -> SeriesScreen(app, screen.state) { v, queue ->
                stack.add(Screen.Detail(v, screen.state.info.name, screen.state.info.id, queue))
            }
            is Screen.Detail -> key(screen) { DetailScreen(app, screen.video, screen.seriesName, screen.seriesId, screen.queue) }
        }
    }
    app.update?.let { UpdateDialog(it) { app.update = null } }
}

/** Menüleiste oben: Rubriken wechseln beim Daraufgehen (wie bei TV-Oberflächen üblich). */
@Composable
internal fun TopBar(app: AppState) {
    Row(Modifier.fillMaxWidth().padding(start = PAGE_PADDING, end = PAGE_PADDING, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(TvColors.Accent), contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("Mediathek", color = TvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(28.dp))
        TabRow(selectedTabIndex = app.tab.ordinal) {
            Tab.entries.forEach { t ->
                Tab(
                    selected = app.tab == t,
                    onFocus = { app.tab = t },
                    onClick = { app.tab = t },
                    colors = TabDefaults.pillIndicatorTabColors(
                        contentColor = TvColors.TextMuted,
                        selectedContentColor = TvColors.Text,
                        focusedContentColor = TvColors.Background,
                        focusedSelectedContentColor = TvColors.Background,
                    ),
                ) {
                    Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun MainContent(app: AppState, nav: Nav, onSearch: () -> Unit, onRetry: () -> Unit, onCheckUpdate: () -> Unit) {
    val tab = app.tab
    if (tab == Tab.SETTINGS) { SettingsPage(app, onCheckUpdate); return }
    if (tab == Tab.SUCHE) { SearchPage(app, nav, onSearch); return }

    val f = app.filtered
    if (f == null) {
        if (app.loading) Hint("Katalog wird geladen …", Modifier.padding(start = PAGE_PADDING, top = 24.dp))
        else ErrorBox("Katalog konnte nicht geladen werden. Bitte Internetverbindung prüfen.", onRetry)
        return
    }
    when (tab) {
        Tab.LIVE -> LivePage(app, nav)
        Tab.LISTE -> ShelfPage(app, tab, personalShelves(tab), nav,
            empty = "Noch nichts gemerkt. In den Details „+ Meine Liste“ wählen – angefangene Sendungen erscheinen hier automatisch.")
        else -> {
            val base = remember(f, tab) { buildShelves(tab, f) }
            ShelfPage(app, tab, personalShelves(tab) + base, nav)
        }
    }
}
