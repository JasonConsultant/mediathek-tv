@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
    LaunchedEffect(Unit) { while (true) { delay(30_000); app.clock = nowSec() } }

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
                    is LiveTile -> {
                        val channels = app.filtered?.live.orEmpty()
                        PlayerActivity.playLive(context, tile.channel, channels,
                            channels.filter { app.playableHere(it) }.map { it.name }.toSet())
                    }
                    is AllTile -> tile.spec?.let { browse(it) } ?: tile.tab?.let { tab(it); app.restoreFocus() }
                }
            }
            override fun browse(spec: BrowseSpec) { stack.add(Screen.Browse(BrowseState(spec))) }
            override fun tab(tab: Tab) { app.tab = tab }
        }
    }

    // Zurück: Unterseite schließen → zur Startseite → App verlassen
    BackHandler(enabled = stack.size > 1 || app.tab != Tab.START) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else app.tab = Tab.START
        app.restoreFocus()
    }

    // testTagsAsResourceId: der Geräte-Test findet Menüpunkte und Fokus über ihre Kennung
    Box(Modifier.fillMaxSize().background(TvColors.Background).semantics { testTagsAsResourceId = true }) {
        when (val screen = stack.last()) {
            Screen.Main -> SideMenu(app) {
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

    // Letzter Absturz (falls es einen gab): Fehlerstelle anzeigen, damit man sie melden kann
    var crash by remember { mutableStateOf(CrashReport.take(context)) }
    crash?.let { CrashDialog(it) { crash = null } }
}

/** Symbol je Rubrik. */
private val Tab.icon: ImageVector get() = when (this) {
    Tab.START -> Icons.Outlined.Home
    Tab.LIVE -> Icons.Outlined.LiveTv
    Tab.SERIEN -> Icons.Outlined.Tv
    Tab.FILME -> Icons.Outlined.Movie
    Tab.KRIMIS -> Icons.Outlined.Fingerprint
    Tab.DOKUS -> Icons.Outlined.Public
    Tab.LISTE -> Icons.Outlined.BookmarkBorder
    Tab.SUCHE -> Icons.Outlined.Search
    Tab.SETTINGS -> Icons.Outlined.Settings
}

/**
 * Seitenleiste links wie bei TiviMate: eingeklappt nur Symbole, mit "Links" klappt sie auf –
 * und zwar immer auf der aktuellen Rubrik. Die Seite wechselt erst, wenn der Fokus kurz auf
 * einem Punkt ruht; so bleibt das Durchblättern flüssig, auch bei schweren Seiten.
 */
@Composable
internal fun SideMenu(app: AppState, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var hovered by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(hovered) {
        val t = hovered ?: return@LaunchedEffect
        delay(MENU_SWITCH_DELAY)
        app.tab = t
    }
    ModalNavigationDrawer(
        drawerContent = { open ->
            Column(
                Modifier.fillMaxHeight().background(
                    Brush.horizontalGradient(listOf(TvColors.Surface, TvColors.Surface.copy(alpha = if (open == DrawerValue.Open) 0.97f else 0.0f)))
                ).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)
                    // Links aus der Seite: Fokus landet auf der aktuellen Rubrik (nicht auf dem Punkt auf gleicher Höhe)
                    .focusProperties { enter = { requesters.getValue(app.tab) } }
                    .focusGroup(),
                verticalArrangement = Arrangement.Center,
            ) {
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = app.tab == t,
                        // OK: Rubrik öffnen – der Fokus springt auf die gemerkte Kachel der Seite, das Menü schließt sich.
                        // Falls diese Kachel gerade nicht aufgebaut ist (weit gescrollt), einfach nach rechts auf die Seite.
                        onClick = {
                            hovered = null
                            app.tab = t
                            app.restoreFocus()
                            scope.launch {
                                withFrameNanos { }; withFrameNanos { }
                                if (!app.pageFocused) runCatching { focus.moveFocus(FocusDirection.Right) }
                            }
                        },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.testTag("tab-${t.name}").focusRequester(requesters.getValue(t)).onFocusChanged {
                            if (!it.isFocused) return@onFocusChanged
                            app.menuFocused = true
                            app.pageFocused = false
                            hovered = t
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            contentColor = TvColors.TextMuted,
                            selectedContentColor = TvColors.Text,
                            selectedContainerColor = TvColors.SurfaceHigh,
                            focusedContainerColor = Color.White,
                            focusedContentColor = TvColors.Background,
                            focusedSelectedContainerColor = Color.White,
                            focusedSelectedContentColor = TvColors.Background,
                        ),
                    ) { Text(t.label, fontSize = 15.sp) }
                }
            }
        },
        scrimBrush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)),
    ) {
        // "Links" am linken Rand der Seite führt immer auf die aktuelle Rubrik im Menü –
        // nicht auf den Menüpunkt, der zufällig auf gleicher Höhe liegt.
        Box(
            Modifier.fillMaxSize().padding(start = RAIL_WIDTH)
                .focusProperties {
                    exit = { dir -> if (dir == FocusDirection.Left) requesters.getValue(app.tab) else FocusRequester.Default }
                }
                .focusGroup()
        ) { content() }
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
        Tab.LIVE -> TvPage(app, nav)
        Tab.LISTE -> ShelfPage(app, tab, personalShelves(tab), nav,
            empty = "Noch nichts gemerkt. In den Details „+ Meine Liste“ wählen – angefangene Sendungen erscheinen hier automatisch.")
        else -> {
            val base = remember(f, tab) { buildShelves(tab, f) }
            ShelfPage(app, tab, personalShelves(tab) + base, nav)
        }
    }
}
