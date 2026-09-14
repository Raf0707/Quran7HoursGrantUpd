package raf.quran7hours.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class NavItem(val label: String, val icon: ImageVector, val route: AppRoute)

class AppNavigator(initial: AppRoute = AppRoute.Home) {
    private val stack = mutableStateListOf<AppRoute>(initial)
    val route: AppRoute get() = stack.last()
    fun go(route: AppRoute) {
        if (stack.lastOrNull() == route) return
        stack += route
    }
    fun replace(route: AppRoute) {
        if (stack.isEmpty()) stack += route else stack[stack.lastIndex] = route
    }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Quran7HoursApp() {
    val context = LocalContext.current
    val repository = remember { QuranRepository(context.applicationContext) }
    val preferences = remember { AppPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val audio = remember { QuranAudioController(context.applicationContext, repository, preferences, scope) }
    val settings by preferences.settings.collectAsState()
    val playerTrack by audio.track.collectAsState()
    val hadrVisible by audio.hadrVisible.collectAsState()
    val navigator = remember { AppNavigator() }
    var coordinate by remember { mutableStateOf(ReaderCoordinate()) }
    var menuSheet by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var readerMenuRequest by remember { mutableStateOf(0) }

    DisposableEffect(Unit) { onDispose { audio.release() } }
    KeepScreenAwakeEffect(settings.keepAwake)
    LaunchedEffect(Unit) {
        repository.startQuranTextPreload()
        startQpcStartupPreload(context.applicationContext, settings.tajweed)
        coroutineScope {
            launch { repository.prewarmMushafStaticData() }
            launch { repository.prewarmMushafAround(1) }
            launch { repository.prewarmSurah(1) }
            launch { prewarmQpcAround(context.applicationContext, 1, settings.tajweed) }
        }
    }
    LaunchedEffect(settings.tajweed) {
        startQpcFontPreload(context.applicationContext, settings.tajweed)
        prewarmQpcAround(context.applicationContext, coordinate.page, settings.tajweed)
    }
    LaunchedEffect(settings.reciter) { audio.reloadForReciter() }

    BackHandler(enabled = true) {
        when {
            menuSheet -> menuSheet = false
            jumpDialog -> jumpDialog = false
            else -> navigator.back()
        }
    }

    QuranTheme(settings) {
        AdaptiveContent { metrics ->
            val readRoute = readingRoute(
                settings.readingMode,
                coordinate.surah,
                coordinate.ayah,
                coordinate.page
            )
            val navItems = listOf(
                NavItem("Главная", Icons.Default.Home, AppRoute.Home),
                NavItem("Читать", Icons.Default.MenuBook, readRoute),
                NavItem("Закладки", Icons.Default.Bookmark, AppRoute.Bookmarks),
                NavItem("Аудио", Icons.Default.Headphones, AppRoute.Audio),
                NavItem("Настройки", Icons.Default.Settings, AppRoute.Settings)
            )

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    if (metrics.isWide) {
                        AppRail(
                            metrics,
                            navItems,
                            navigator.route,
                            onNavigate = navigator::go,
                            onDialog = { jumpDialog = true }
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AppTopBar(
                            metrics,
                            navigator.route,
                            settings,
                            preferences,
                            onHome = { navigator.go(AppRoute.Home) },
                            onJump = { jumpDialog = true },
                            onBookmarks = { navigator.go(AppRoute.Bookmarks) },
                            playerInToolbar = MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.TOOLBAR && (playerTrack != null || hadrVisible),
                            onRestorePlayer = { MiniPlayerUiPrefs.expand() },
                            onReaderMenu = {
                                when (navigator.route) {
                                    is AppRoute.AyahRoute, is AppRoute.PageRoute -> readerMenuRequest++
                                    else -> navigator.go(AppRoute.Settings)
                                }
                            },
                            onMenu = { menuSheet = true }
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AnimatedContent(
                                targetState = navigator.route,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    val direction = readerPageTurnDirection(initialState, targetState)
                                    if (direction > 0) {
                                        // RTL Mushaf/page navigation: the next page enters from the left
                                        // while the current page follows the swipe to the right.
                                        (slideInHorizontally(tween(280)) { -it } + fadeIn(tween(180))) togetherWith
                                            (slideOutHorizontally(tween(280)) { it } + fadeOut(tween(140)))
                                    } else if (direction < 0) {
                                        (slideInHorizontally(tween(280)) { it } + fadeIn(tween(180))) togetherWith
                                            (slideOutHorizontally(tween(280)) { -it } + fadeOut(tween(140)))
                                    } else {
                                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                    }
                                },
                                label = "screen"
                            ) { route ->
                                when (route) {
                                    AppRoute.Home -> HomeScreen(metrics, repository, preferences, navigator)
                                    AppRoute.Audio -> AudioScreen(metrics, repository, preferences, audio)
                                    AppRoute.Bookmarks -> BookmarksScreen(metrics, repository, preferences, navigator)
                                    AppRoute.Settings -> SettingsScreen(metrics, repository, preferences)
                                    is AppRoute.AyahRoute -> ReaderScreen(metrics, repository, preferences, audio, navigator, route, menuRequest = readerMenuRequest, onCoordinate = { coordinate = it })
                                    is AppRoute.PageRoute -> ReaderScreen(metrics, repository, preferences, audio, navigator, route, menuRequest = readerMenuRequest, onCoordinate = { coordinate = it })
                                }
                            }

                            // Full-screen overlay host: MiniPlayer itself decides its vertical
                            // position and only its visible surface consumes touches. It never
                            // participates in reader measurement, so Mushaf geometry is unchanged.
                            MiniPlayer(
                                m = metrics,
                                audio = audio,
                                repository = repository,
                                preferences = preferences,
                                coordinate = coordinate,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxSize()
                                    .navigationBarsPadding()
                            )
                        }
                    }
                }
            }

            if (menuSheet) {
                AppNavigationSheet(
                    m = metrics,
                    items = navItems,
                    current = navigator.route,
                    onDismiss = { menuSheet = false },
                    onNavigate = {
                        menuSheet = false
                        navigator.go(it)
                    },
                    onQuickJump = {
                        menuSheet = false
                        jumpDialog = true
                    }
                )
            }

            if (jumpDialog) {
                NavigationDialog(
                    metrics,
                    repository,
                    coordinate,
                    readingMode = settings.readingMode,
                    onDismiss = { jumpDialog = false }
                ) { route ->
                    jumpDialog = false
                    navigator.go(route)
                }
            }
        }
    }
}

private fun readerPageTurnDirection(from: AppRoute, to: AppRoute): Int = when {
    from is AppRoute.PageRoute && to is AppRoute.PageRoute && from.mode == to.mode ->
        to.page.compareTo(from.page)
    from is AppRoute.AyahRoute && to is AppRoute.AyahRoute ->
        when {
            to.surah != from.surah -> to.surah.compareTo(from.surah)
            else -> to.ayah.compareTo(from.ayah)
        }
    else -> 0
}

@Composable
private fun AppTopBar(
    m: AdaptiveMetrics,
    route: AppRoute,
    settings: AppSettings,
    prefs: AppPreferences,
    onHome: () -> Unit,
    onJump: () -> Unit,
    onBookmarks: () -> Unit,
    playerInToolbar: Boolean,
    onRestorePlayer: () -> Unit,
    onReaderMenu: () -> Unit,
    onMenu: () -> Unit
) {
    val darkNow = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    Surface(tonalElevation = m.unit * .4f) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = m.md, vertical = m.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (playerInToolbar) Arrangement.SpaceEvenly else Arrangement.spacedBy(m.sm)
        ) {
            val actionSize = m.touch * .76f
            val actionIcon = m.iconSmall * .86f
            val iconTint = MaterialTheme.colorScheme.onSurface

            fun Modifier.actionSize() = this.width(actionSize).height(actionSize)

            IconButton(onClick = onHome, modifier = Modifier.actionSize()) {
                Icon(Icons.Default.Home, contentDescription = "Главная", modifier = Modifier.width(actionIcon).height(actionIcon), tint = iconTint)
            }

            if (!playerInToolbar) {
                Text(
                    "Коран за 7 часов",
                    fontSize = m.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            if (route is AppRoute.AyahRoute || route is AppRoute.PageRoute) {
                IconButton(onClick = onJump, modifier = Modifier.actionSize()) {
                    Icon(Icons.Default.Navigation, contentDescription = "Перейти", modifier = Modifier.width(actionIcon).height(actionIcon), tint = iconTint)
                }
            }
            IconButton(
                onClick = { prefs.updateSettings { it.copy(themeMode = if (darkNow) ThemeMode.LIGHT else ThemeMode.DARK) } },
                modifier = Modifier.actionSize()
            ) {
                Icon(
                    if (darkNow) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (darkNow) "Светлая тема" else "Тёмная тема",
                    modifier = Modifier.width(actionIcon).height(actionIcon),
                    tint = iconTint
                )
            }
            IconButton(onClick = onReaderMenu, modifier = Modifier.actionSize()) {
                Icon(Icons.Default.MenuBook, contentDescription = "Меню чтения", tint = iconTint, modifier = Modifier.width(actionIcon).height(actionIcon))
            }
            IconButton(onClick = onBookmarks, modifier = Modifier.actionSize()) {
                Icon(Icons.Default.Bookmark, contentDescription = "Закладки", modifier = Modifier.width(actionIcon).height(actionIcon), tint = iconTint)
            }
            if (playerInToolbar) {
                IconButton(onClick = onRestorePlayer, modifier = Modifier.actionSize()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Показать плеер", modifier = Modifier.width(actionIcon).height(actionIcon), tint = iconTint)
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.actionSize()) {
                Icon(Icons.Default.Menu, contentDescription = "Меню", modifier = Modifier.width(actionIcon).height(actionIcon), tint = iconTint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigationSheet(
    m: AdaptiveMetrics,
    items: List<NavItem>,
    current: AppRoute,
    onDismiss: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
    onQuickJump: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = m.md, vertical = m.sm),
            verticalArrangement = Arrangement.spacedBy(m.xs)
        ) {
            Text("Навигация", fontSize = m.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = m.sm, vertical = m.sm))
            Text(
                "Пункты больше не занимают нижнюю часть экрана — всё меню открывается здесь.",
                fontSize = m.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = m.sm)
            )
            Spacer(Modifier.height(m.xs))
            items.take(2).forEach { item ->
                NavigationSheetRow(m, item.label, item.icon, sameSection(current, item.route)) { onNavigate(item.route) }
            }
            NavigationSheetRow(m, "Перейти", Icons.Default.Navigation, false, onQuickJump)
            items.drop(2).forEach { item ->
                NavigationSheetRow(m, item.label, item.icon, sameSection(current, item.route)) { onNavigate(item.route) }
            }
            Spacer(Modifier.height(m.md))
        }
    }
}

@Composable
private fun NavigationSheetRow(
    m: AdaptiveMetrics,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(m.corner * .62f),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.md)
        ) {
            Icon(icon, null, modifier = Modifier.width(m.iconSmall).height(m.iconSmall), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = m.body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppRail(m: AdaptiveMetrics, items: List<NavItem>, current: AppRoute, onNavigate: (AppRoute) -> Unit, onDialog: () -> Unit) {
    NavigationRail(Modifier.fillMaxHeight().width(m.unit * 18f).navigationBarsPadding(), header = {
        Spacer(Modifier.height(m.lg))
        Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(m.icon).height(m.icon))
        Spacer(Modifier.height(m.md))
    }) {
        items.take(2).forEach { item -> RailItem(m, item, current, onNavigate) }
        NavigationRailItem(selected = false, onClick = onDialog, icon = { Icon(Icons.Default.Navigation, null, modifier = Modifier.width(m.iconSmall).height(m.iconSmall)) }, label = { Text("Перейти", fontSize = m.bodySmall) })
        items.drop(2).forEach { item -> RailItem(m, item, current, onNavigate) }
    }
}

@Composable
private fun RailItem(m: AdaptiveMetrics, item: NavItem, current: AppRoute, onNavigate: (AppRoute) -> Unit) {
    NavigationRailItem(
        selected = sameSection(current, item.route),
        onClick = { onNavigate(item.route) },
        icon = { Icon(item.icon, null, modifier = Modifier.width(m.iconSmall).height(m.iconSmall)) },
        label = { Text(item.label, fontSize = m.bodySmall) }
    )
}

private fun sameSection(current: AppRoute, target: AppRoute): Boolean = when (target) {
    AppRoute.Home -> current == AppRoute.Home
    AppRoute.Audio -> current == AppRoute.Audio
    AppRoute.Bookmarks -> current == AppRoute.Bookmarks
    AppRoute.Settings -> current == AppRoute.Settings
    is AppRoute.AyahRoute -> current is AppRoute.AyahRoute || current is AppRoute.PageRoute
    is AppRoute.PageRoute -> current is AppRoute.AyahRoute || current is AppRoute.PageRoute
}
