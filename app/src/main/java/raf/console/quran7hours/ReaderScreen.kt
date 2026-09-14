package raf.quran7hours.app

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ReaderPayload(
    val meta: QuranMeta,
    val surah: SurahData? = null,
    val pageAyahs: List<PageAyah> = emptyList(),
    val page: Int,
    val mode: ReadingMode,
    val currentSurah: Int,
    val currentAyah: Int,
    val juz: Int,
    val range: String
)

private var lastHandledReaderMenuRequest: Int = Int.MIN_VALUE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    m: AdaptiveMetrics,
    repository: QuranRepository,
    preferences: AppPreferences,
    audio: QuranAudioController,
    navigator: AppNavigator,
    route: AppRoute,
    menuRequest: Int,
    onCoordinate: (ReaderCoordinate) -> Unit
) {
    val settings by preferences.settings.collectAsState()
    val payload by produceState<ReaderPayload?>(null, route) {
        val meta = repository.meta()
        when (route) {
            is AppRoute.AyahRoute -> {
                val s = repository.surah(route.surah)
                val ay = s.ayahs.firstOrNull { it.a == route.ayah } ?: s.ayahs.first()
                val pg = repository.page(ay.p)
                value = ReaderPayload(meta, s, emptyList(), ay.p, ReadingMode.SURAH, s.id, ay.a, ay.j, pageRange(pg))
            }
            is AppRoute.PageRoute -> {
                val pg = repository.page(route.page)
                val first = pg.firstOrNull()
                value = ReaderPayload(
                    meta = meta,
                    surah = null,
                    pageAyahs = pg,
                    page = route.page.coerceIn(1, 604),
                    mode = route.mode,
                    currentSurah = first?.surah ?: 1,
                    currentAyah = first?.a ?: 1,
                    juz = first?.j ?: 1,
                    range = pageRange(pg)
                )
            }
            else -> Unit
        }
    }
    val data = payload ?: return LoadingPane(m, "Открываем Коран…")
    val context = LocalContext.current

    // In Mushaf mode the pager owns page movement locally. Do not replace the app
    // route after every swipe: doing so recreated the whole ReaderScreen through
    // the outer AnimatedContent and caused the visible hitch around half-swipe.
    var visibleMushafPage by remember(data.mode, data.page) { mutableIntStateOf(data.page) }
    val visibleMushafPayload by produceState<ReaderPayload?>(
        initialValue = if (data.mode == ReadingMode.MUSHAF && visibleMushafPage == data.page) data else null,
        data.mode, visibleMushafPage, data.meta
    ) {
        if (data.mode != ReadingMode.MUSHAF) {
            value = data
        } else {
            value = withContext(Dispatchers.IO) {
                val pg = runCatching { repository.page(visibleMushafPage) }.getOrDefault(emptyList())
                val first = pg.firstOrNull()
                ReaderPayload(
                    meta = data.meta,
                    surah = null,
                    pageAyahs = pg,
                    page = visibleMushafPage,
                    mode = ReadingMode.MUSHAF,
                    currentSurah = first?.surah ?: data.currentSurah,
                    currentAyah = first?.a ?: 1,
                    juz = first?.j ?: data.juz,
                    range = pageRange(pg)
                )
            }
        }
    }
    val effectiveData = if (data.mode == ReadingMode.MUSHAF) (visibleMushafPayload ?: data) else data

    val alaut by produceState<Map<String, AlautdinovEntry>>(emptyMap()) {
        value = runCatching { repository.alautdinov() }.getOrDefault(emptyMap())
    }

    LaunchedEffect(effectiveData.page, settings.tajweed) {
        repository.prewarmMushafAround(effectiveData.page)
        prewarmQpcAround(context.applicationContext, effectiveData.page, settings.tajweed)
    }
    LaunchedEffect(effectiveData.currentSurah) { repository.prewarmSurah(effectiveData.currentSurah) }
    LaunchedEffect(effectiveData.currentSurah, effectiveData.currentAyah, effectiveData.page) {
        preferences.addRecent("${effectiveData.currentSurah}:${effectiveData.currentAyah}")
        onCoordinate(ReaderCoordinate(effectiveData.currentSurah, effectiveData.currentAyah, effectiveData.page, effectiveData.juz, effectiveData.range))
    }
    LaunchedEffect(data.mode) {
        if (settings.readingMode != data.mode) {
            preferences.updateSettings { it.copy(readingMode = data.mode) }
        }
    }

    var selectedAyah by remember(effectiveData.page, effectiveData.currentSurah, effectiveData.currentAyah) {
        mutableStateOf(effectiveData.currentSurah to effectiveData.currentAyah)
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetSection by remember { mutableStateOf(ReaderSheetSection.AUDIO) }

    LaunchedEffect(menuRequest) {
        if (menuRequest > 0 && menuRequest != lastHandledReaderMenuRequest) {
            lastHandledReaderMenuRequest = menuRequest
            sheetSection = ReaderSheetSection.AUDIO
            sheetOpen = true
        }
    }

    fun next() {
        when (data.mode) {
            ReadingMode.SURAH -> if (data.currentSurah < 114) navigator.replace(AppRoute.AyahRoute(data.currentSurah + 1, 1))
            ReadingMode.PAGE, ReadingMode.MUSHAF -> if (data.page < 604) navigator.replace(AppRoute.PageRoute(data.page + 1, data.mode))
        }
    }
    fun previous() {
        when (data.mode) {
            ReadingMode.SURAH -> if (data.currentSurah > 1) navigator.replace(AppRoute.AyahRoute(data.currentSurah - 1, 1))
            ReadingMode.PAGE, ReadingMode.MUSHAF -> if (data.page > 1) navigator.replace(AppRoute.PageRoute(data.page - 1, data.mode))
        }
    }

    val readerGestureModifier = if (data.mode == ReadingMode.MUSHAF) {
        Modifier.fillMaxSize().navigationBarsPadding()
    } else {
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .readerHorizontalSwipe(
                key = "${data.mode}:${data.page}:${data.currentSurah}",
                onSwipeRight = ::next,
                onSwipeLeft = ::previous
            )
    }

    Box(readerGestureModifier) {
        when (data.mode) {
            ReadingMode.MUSHAF -> MushafPagerReader(
                m = m,
                data = effectiveData,
                repository = repository,
                settings = settings,
                audio = audio,
                onPageSettled = { visibleMushafPage = it },
                onAyahSelected = { surah, ayah -> selectedAyah = surah to ayah }
            )
            ReadingMode.SURAH, ReadingMode.PAGE -> StandardReader(
                m = m,
                data = data,
                repository = repository,
                preferences = preferences,
                settings = settings,
                alaut = alaut,
                audio = audio,
                navigator = navigator,
                onAyahSelected = { surah, ayah -> selectedAyah = surah to ayah }
            )
        }

    }

    if (sheetOpen) {
        ReaderBottomSheet(
            m = m,
            data = effectiveData,
            repository = repository,
            preferences = preferences,
            settings = settings,
            alaut = alaut,
            audio = audio,
            navigator = navigator,
            selectedSurah = selectedAyah.first,
            selectedAyah = selectedAyah.second,
            section = sheetSection,
            onSection = { sheetSection = it },
            onDismiss = { sheetOpen = false },
            onModeChange = { mode ->
                preferences.updateSettings { it.copy(readingMode = mode) }
                sheetOpen = false
                navigator.replace(readingRoute(mode, selectedAyah.first, selectedAyah.second, effectiveData.page))
            }
        )
    }
}

private fun Modifier.readerHorizontalSwipe(
    key: Any,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit
): Modifier = pointerInput(key) {
    var totalDrag = 0f
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onDragCancel = { totalDrag = 0f },
        onDragEnd = {
            val threshold = size.width * .16f
            when {
                totalDrag > threshold -> onSwipeRight()   // RTL: right swipe advances.
                totalDrag < -threshold -> onSwipeLeft()
            }
            totalDrag = 0f
        }
    ) { _, amount ->
        totalDrag += amount
    }
}

@Composable
private fun StandardReader(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    preferences: AppPreferences,
    settings: AppSettings,
    alaut: Map<String, AlautdinovEntry>,
    audio: QuranAudioController,
    navigator: AppNavigator,
    onAyahSelected: (Int, Int) -> Unit
) {
    val track by audio.track.collectAsState()
    val playing by audio.playing.collectAsState()
    val ayahs: List<PageAyah> = if (data.mode == ReadingMode.SURAH) {
        val s = data.surah!!
        s.ayahs.map { PageAyah(s.id, s.nameRu, s.nameAr, it) }
    } else data.pageAyahs
    val listState = rememberLazyListState()

    LaunchedEffect(data.mode, data.currentSurah, data.currentAyah, ayahs.size) {
        if (data.mode == ReadingMode.SURAH && data.currentAyah > 1) {
            val verseIndex = ayahs.indexOfFirst { it.a == data.currentAyah }
            if (verseIndex >= 0) listState.scrollToItem(2 + verseIndex)
        }
    }

    LazyColumn(state = listState, contentPadding = m.pagePadding, verticalArrangement = Arrangement.spacedBy(m.md)) {
        item { ReaderInfoHeader(m, data) }
        if (data.mode == ReadingMode.SURAH && data.surah != null) {
            item { SurahBanner(m, data.surah, settings, audio) }
        } else if (data.mode == ReadingMode.PAGE && data.page == 1) {
            item { PageOneBismillah(m, settings, audio, data.meta.surahs.firstOrNull()?.nameRu ?: "Аль-Фатиха") }
        }
        items(ayahs, key = { "${it.surah}:${it.a}" }) { row ->
            val key = "${row.surah}:${row.a}"
            val active = track?.kind == TrackKind.AYAH && track?.surah == row.surah && row.a in (track?.ayah ?: -1)..(track?.endAyah ?: -1)
            VerseCard(
                m = m,
                row = row,
                repository = repository,
                preferences = preferences,
                settings = settings,
                alaut = alaut[key],
                active = active,
                playing = playing && active,
                audio = audio,
                navigator = navigator,
                onAyahSelected = onAyahSelected
            )
        }
    }
}

@Composable
private fun ReaderModeBar(m: AdaptiveMetrics, data: ReaderPayload, onMode: (ReadingMode) -> Unit) {
    val colors = LocalQuranColors.current
    Surface(
        color = colors.bg,
        shadowElevation = m.xs * .16f,
        border = BorderStroke(m.xs * .08f, colors.line.copy(alpha=.72f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=m.sm, vertical=m.xs),
            horizontalArrangement=Arrangement.spacedBy(m.xs)
        ) {
            ModeButton(m, "Сура", Icons.Default.List, data.mode==ReadingMode.SURAH, Modifier.weight(1f)) { onMode(ReadingMode.SURAH) }
            ModeButton(m, "Страница", Icons.Default.ViewDay, data.mode==ReadingMode.PAGE, Modifier.weight(1f)) { onMode(ReadingMode.PAGE) }
            ModeButton(m, "Мусхаф", Icons.Default.MenuBook, data.mode==ReadingMode.MUSHAF, Modifier.weight(1f)) { onMode(ReadingMode.MUSHAF) }
        }
    }
}

@Composable
private fun ModeButton(m:AdaptiveMetrics,label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){
    val colors=LocalQuranColors.current
    Surface(
        onClick=onClick,
        modifier=modifier,
        shape=RoundedCornerShape(m.corner*.48f),
        color=if(selected) colors.surface else colors.surface2.copy(alpha=.76f),
        border=BorderStroke(m.xs*.08f,if(selected) colors.line else colors.line.copy(alpha=.62f)),
        shadowElevation=if(selected)m.xs*.10f else m.xs*.01f
    ){
        Row(
            Modifier.fillMaxWidth().padding(horizontal=m.xs,vertical=m.sm),
            horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically
        ){
            Icon(icon,null,Modifier.size(m.iconSmall*.72f),tint=if(selected)colors.accent else colors.muted)
            Text(label,fontSize=m.bodySmall,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,color=if(selected)colors.text else colors.muted,modifier=Modifier.padding(start=m.xs))
        }
    }
}

@Composable
private fun ReaderInfoHeader(m: AdaptiveMetrics, data: ReaderPayload) {
    val sm = data.meta.surahs.getOrNull(data.currentSurah - 1)
    val colors=LocalQuranColors.current
    Surface(shape = RoundedCornerShape(m.corner), color=colors.surface, border=BorderStroke(m.xs*.08f,colors.line.copy(alpha=.75f))) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            if (m.isWide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sm?.nameAr.orEmpty(), fontFamily = QuranFont, fontSize = m.heading, color = colors.muted)
                        Text(sm?.nameRu ?: "Коран", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(m.md)) {
                        HeaderFact(m, "${data.currentSurah}:${data.currentAyah}", "сура · аят")
                        HeaderFact(m, data.page.toString(), "страница")
                        HeaderFact(m, data.juz.toString(), "джуз")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sm?.nameAr.orEmpty(), fontFamily = QuranFont, fontSize = m.body, color = colors.muted)
                        Text(sm?.nameRu ?: "Коран", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeaderFact(m, "${data.currentSurah}:${data.currentAyah}", "сура · аят")
                    HeaderFact(m, data.page.toString(), "страница")
                    HeaderFact(m, data.juz.toString(), "джуз")
                }
            }
            Surface(shape=RoundedCornerShape(m.corner*.38f),color=colors.surface2.copy(alpha=.55f)){
                Text("Аяты: ${data.range}", fontSize=m.bodySmall, color=colors.muted, modifier=Modifier.fillMaxWidth().padding(horizontal=m.md,vertical=m.sm))
            }
        }
    }
}

@Composable
private fun HeaderFact(m: AdaptiveMetrics, value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, fontSize = m.body, fontWeight = FontWeight.Bold)
        Text(label, fontSize = m.bodySmall * .86f, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SurahBanner(m: AdaptiveMetrics, s: SurahData, settings: AppSettings, audio: QuranAudioController) {
    val track by audio.track.collectAsState()
    val playing by audio.playing.collectAsState()
    Surface(shape = RoundedCornerShape(m.corner), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha=.72f)) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сура ${s.id}", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(s.nameRu, fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                    Text(s.meaning, fontSize = m.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s.nameAr, fontFamily = QuranFont, fontSize = m.title, color = MaterialTheme.colorScheme.primary)
            }
            if (s.bismillah != null && s.id != 9) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha=.2f))
                if (settings.tajweed || settings.arabicFont == "qpc-v4") {
                    if (s.id == 1) QpcV4BismillahText(m, settings)
                    else QpcV4SurahBismillahText(m, s.ayahs.firstOrNull()?.p ?: 1, settings)
                } else Text(
                    s.bismillah.ar,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    fontFamily = QuranFont, fontSize = m.arabicBase * settings.arabicScale,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val active = track?.kind == TrackKind.BISMILLAH && track?.surah == s.id
                Button(
                    onClick = { audio.playBismillah(s.id, s.nameRu) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(if (playing && active) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier=Modifier.size(m.iconSmall))
                    Text("Басмала · отдельно", fontSize=m.bodySmall, modifier=Modifier.padding(start=m.sm))
                }
            }

            // Restore the original surah description from the bundled Azan.ru
            // corpus. The web version shows this immediately below the basmala.
            s.intro?.tafsir?.trim()?.takeIf { it.isNotBlank() }?.let { description ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .20f))
                Text(
                    "Описание суры",
                    fontSize = m.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    fontSize = m.bodySmall,
                    lineHeight = m.bodySmall * 1.45f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PageOneBismillah(m: AdaptiveMetrics, settings: AppSettings, audio: QuranAudioController, name: String) {
    val track by audio.track.collectAsState(); val playing by audio.playing.collectAsState()
    val active = track?.kind == TrackKind.BISMILLAH && track?.surah == 1
    val text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"
    Surface(shape=RoundedCornerShape(m.corner), tonalElevation=m.unit*.15f) {
        Column(Modifier.fillMaxWidth().padding(m.lg), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(m.md)) {
            if (settings.tajweed || settings.arabicFont == "qpc-v4") QpcV4BismillahText(m, settings)
            else Text(text, fontFamily=QuranFont, fontSize=m.arabicBase*settings.arabicScale, textAlign=TextAlign.Center)
            Button(onClick={audio.playBismillah(1,name)}) {
                Icon(if(playing&&active) Icons.Default.Pause else Icons.Default.PlayArrow,null,Modifier.size(m.iconSmall)); Text("Басмала · отдельно",fontSize=m.bodySmall,modifier=Modifier.padding(start=m.sm))
            }
        }
    }
}

private val bookmarkColors = listOf(0xFFDF6D62, 0xFFD9A441, 0xFF5CA978, 0xFF4E8ECB, 0xFF8F70C3, 0xFFB8699B, 0xFF6F777C)

@Composable
private fun VerseCard(
    m: AdaptiveMetrics,
    row: PageAyah,
    repository: QuranRepository,
    preferences: AppPreferences,
    settings: AppSettings,
    alaut: AlautdinovEntry?,
    active: Boolean,
    playing: Boolean,
    audio: QuranAudioController,
    navigator: AppNavigator,
    onAyahSelected: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val bookmarks by preferences.bookmarks.collectAsState()
    val key = "${row.surah}:${row.a}"
    val mark = bookmarks.firstOrNull { it.key == key }
    var menu by remember { mutableStateOf(false) }
    val pageWords by produceState<List<WordItem>>(emptyList(), row.p, row.surah, row.a) {
        value = wordsForAppAyah(repository.wordPage(row.p), row.surah, row.a)
    }
    val baseColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.46f) else MaterialTheme.colorScheme.surface
    Surface(
        onClick = {
            onAyahSelected(row.surah, row.a)
            audio.playAyah(row.surah, row.a, row.surahName)
        },
        shape = RoundedCornerShape(m.corner),
        color = baseColor,
        tonalElevation = m.unit * .16f,
        border = if(active) BorderStroke(m.unit*.22f, MaterialTheme.colorScheme.primary.copy(alpha=.5f)) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=CircleShape, color=MaterialTheme.colorScheme.primaryContainer, onClick={ onAyahSelected(row.surah,row.a); audio.playAyah(row.surah,row.a,row.surahName) }, modifier=Modifier.size(m.touch*.72f)) {
                    Box(contentAlignment=Alignment.Center){ Text(row.a.toString(),fontSize=m.body,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimaryContainer) }
                }
                IconButton(onClick={ onAyahSelected(row.surah,row.a); audio.playAyah(row.surah,row.a,row.surahName) },modifier=Modifier.size(m.touch*.76f)) {
                    Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,"Воспроизвести",modifier=Modifier.size(m.iconSmall),tint=MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick={menu=true},modifier=Modifier.size(m.touch*.76f)){Icon(Icons.Default.MoreHoriz,"Меню",Modifier.size(m.iconSmall))}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        DropdownMenuItem(text={Text("Копировать")},leadingIcon={Icon(Icons.Default.ContentCopy,null)},onClick={
                            val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(key,"${row.surahName}, ${row.a}\n${row.ayah.ar}\n${row.ayah.tr}"));menu=false
                        })
                        DropdownMenuItem(text={Text("Поделиться")},leadingIcon={Icon(Icons.Default.Share,null)},onClick={shareAyah(context,"${row.surahName}, ${row.a}",row.ayah.ar,row.ayah.tr);menu=false})
                        if(mark!=null) DropdownMenuItem(text={Text("Убрать закладку")},leadingIcon={Icon(Icons.Default.Check,null)},onClick={preferences.removeBookmark(key);menu=false})
                        else {
                            DropdownMenuItem(text={Text("Закладка")},leadingIcon={Icon(Icons.Default.Bookmark,null)},onClick={})
                            Row(Modifier.padding(horizontal=m.md,vertical=m.sm),horizontalArrangement=Arrangement.spacedBy(m.sm)){
                                bookmarkColors.forEach { c -> Surface(onClick={preferences.setBookmark(key,c);menu=false},shape=CircleShape,color=Color(c),modifier=Modifier.size(m.icon)){} }
                            }
                        }
                    }
                }
            }
            if (settings.showArabic) {
                if (settings.tajweed || settings.arabicFont == "qpc-v4") {
                    if (settings.showWordByWord && pageWords.isNotEmpty()) {
                        QpcV4WordByWordAyah(m, row.surah, row.a, row.p, pageWords, settings)
                    } else {
                        QpcV4AyahText(
                            m = m,
                            surah = row.surah,
                            ayah = row.a,
                            page = row.p,
                            settings = settings,
                            fallback = row.ayah.ar
                        )
                    }
                } else if (settings.showWordByWord && pageWords.isNotEmpty()) {
                    WordByWordAyah(m,row.ayah,pageWords,settings)
                } else Text(
                    AnnotatedString(row.ayah.ar),
                    modifier=Modifier.fillMaxWidth(), textAlign=TextAlign.End,
                    fontFamily=arabicFont(settings), fontSize=m.arabicBase*settings.arabicScale,
                    lineHeight=(m.arabicBase*settings.arabicScale)*settings.lineHeight,
                    letterSpacing=m.textUnit*(settings.letterSpacing*.10f), color=MaterialTheme.colorScheme.onSurface
                )
            }
            settings.contentOrder.forEach { block ->
                when(block) {
                    ContentBlock.AZAN_TRANSLATION -> if(settings.showTranslation && row.ayah.tr.isNotBlank()) TextContentBlock(m,"Перевод · Azan.ru",row.ayah.tr,m.body*settings.translationScale)
                    ContentBlock.ALAUTDINOV_TRANSLATION -> if(settings.showTranslation && !alaut?.translation.isNullOrBlank()) TextContentBlock(m,"Перевод · Шамиль Аляутдинов",alaut!!.translation,m.body*settings.translationScale)
                    ContentBlock.AZAN_TAFSIR -> if(settings.showTafsir && (row.ayah.tf.isNotBlank() || !row.ayah.tfBlocks.isNullOrEmpty())) TafsirArticle(m,row.ayah.tfBlocks,row.ayah.tf,settings,scopeKey=key)
                    ContentBlock.ALAUTDINOV_TAFSIR -> if(settings.showTafsir && !alaut?.tafsir.isNullOrBlank()) TextContentBlock(m,"Тафсир · Шамиль Аляутдинов",alaut!!.tafsir,m.body*settings.tafsirScale)
                }
            }
            if(mark!=null) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(m.sm)){Surface(shape=CircleShape,color=Color(mark.color),modifier=Modifier.size(m.sm)){};Text("Закладка",fontSize=m.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable
private fun WordByWordAyah(m: AdaptiveMetrics, ayah: Ayah, words: List<WordItem>, settings: AppSettings) {
    val styled = remember(ayah.tw,settings.tajweed){tajweedWords(ayah.tw,settings.tajweed)}
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(m.md,Alignment.End),verticalArrangement=Arrangement.spacedBy(m.md)) {
            words.forEachIndexed { index,w ->
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(styled.getOrNull(index)?:AnnotatedString(w.arabic),fontFamily=arabicFont(settings),fontSize=m.arabicBase*settings.arabicScale,textAlign=TextAlign.Center)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(w.translation,fontSize=(m.arabicBase*settings.arabicScale)*settings.wordByWordScale,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextContentBlock(m: AdaptiveMetrics, label: String, text: String, size: androidx.compose.ui.unit.TextUnit) {
    Column(verticalArrangement=Arrangement.spacedBy(m.xs)) {
        Text(label.uppercase(),fontSize=m.bodySmall*.88f,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
        Text(text,fontSize=size,lineHeight=size*1.55f)
    }
}

@Composable
private fun arabicFont(settings: AppSettings): FontFamily = rememberArabicFont(settings.arabicFont)

@Composable
private fun ReaderStepper(m:AdaptiveMetrics,data:ReaderPayload,navigator:AppNavigator){
    Surface(shape=RoundedCornerShape(m.corner),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)){
        if(data.mode==ReadingMode.SURAH){
            Row(Modifier.fillMaxWidth().padding(m.md),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                Button(onClick={navigator.go(AppRoute.AyahRoute((data.currentSurah-1).coerceAtLeast(1),1))},enabled=data.currentSurah>1){Text("Предыдущая сура",fontSize=m.bodySmall)}
                Text("Сура ${data.currentSurah}",fontSize=m.body,fontWeight=FontWeight.Bold)
                Button(onClick={navigator.go(AppRoute.AyahRoute((data.currentSurah+1).coerceAtMost(114),1))},enabled=data.currentSurah<114){Text("Следующая сура",fontSize=m.bodySmall)}
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(m.md),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                Button(onClick={navigator.go(AppRoute.PageRoute((data.page+1).coerceAtMost(604),data.mode))},enabled=data.page<604){Text("← ${data.page+1}",fontSize=m.bodySmall)}
                Text("${data.page} / 604",fontSize=m.body,fontWeight=FontWeight.Bold)
                Button(onClick={navigator.go(AppRoute.PageRoute((data.page-1).coerceAtLeast(1),data.mode))},enabled=data.page>1){Text("${data.page-1} →",fontSize=m.bodySmall)}
            }
        }
    }
}

private fun pageRange(pg:List<PageAyah>):String {
    if(pg.isEmpty())return "—"
    val f=pg.first();val l=pg.last()
    return if(f.surah==l.surah) "${f.a}–${l.a}" else "${f.surah}:${f.a} – ${l.surah}:${l.a}"
}

// ---- Native 604-page Mushaf ----
private sealed interface MushafElement {
    data class Header(val surah:Int,val nameRu:String,val nameAr:String,val bismillah:Bismillah?):MushafElement
    data class Line(val line:Int,val words:List<WordItem>,val geometry:MushafLineGeometry?):MushafElement
}

@Composable
private fun MushafPagerReader(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    settings: AppSettings,
    audio: QuranAudioController,
    onPageSettled: (Int) -> Unit,
    onAyahSelected: (Int, Int) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = (data.page - 1).coerceIn(0, 603),
        pageCount = { 604 }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledIndex -> onPageSettled(settledIndex + 1) }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            beyondViewportPageCount = 2,
            key = { it + 1 }
        ) { index ->
            val pageNumber = index + 1
            val pagePayload by produceState<ReaderPayload?>(null, pageNumber) {
                value = withContext(Dispatchers.IO) {
                    val pg = runCatching { repository.page(pageNumber) }.getOrDefault(emptyList())
                    val first = pg.firstOrNull()
                    ReaderPayload(
                        meta = data.meta,
                        surah = null,
                        pageAyahs = pg,
                        page = pageNumber,
                        mode = ReadingMode.MUSHAF,
                        currentSurah = first?.surah ?: data.currentSurah,
                        currentAyah = first?.a ?: 1,
                        juz = first?.j ?: data.juz,
                        range = pageRange(pg)
                    )
                }
            }

            val pageData = pagePayload
            if (pageData != null) {
                MushafReader(
                    m = m,
                    data = pageData,
                    repository = repository,
                    settings = settings,
                    audio = audio,
                    onAyahSelected = onAyahSelected
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Открываем страницу…",
                        fontSize = m.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

    }
}

@Composable
private fun MushafReader(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    settings: AppSettings,
    audio: QuranAudioController,
    onAyahSelected: (Int, Int) -> Unit
) {
    val qpcMode = settings.tajweed || settings.arabicFont == "qpc-v4"

    // Keep the local Unicode fallback warm, but the visible Mushaf itself occupies
    // one fixed viewport: no LazyColumn, no tools below it and no vertical scroll.
    val wordPage by produceState<WordPage?>(null, data.page) {
        value = runCatching { repository.wordPage(data.page) }.getOrNull()
    }
    val geometry by produceState<List<MushafLineGeometry>>(repository.cachedGeometry(data.page), data.page) {
        if (value.isEmpty()) value = runCatching { repository.geometry(data.page) }.getOrDefault(emptyList())
    }
    val surahMap by produceState<Map<Int, SurahData>>(emptyMap(), data.page) {
        value = runCatching {
            data.pageAyahs.map { it.surah }.distinct().associateWith { repository.surah(it) }
        }.getOrDefault(emptyMap())
    }
    val elements = remember(wordPage, geometry, surahMap) {
        wordPage?.let { buildMushafElements(it, geometry, surahMap) }.orEmpty()
    }
    val colors = LocalQuranColors.current

    Surface(
        color = colors.mushafPaper,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.xs * .36f, vertical = m.xs * .20f)
            ) {
                MushafTopLine(m, data)
                HorizontalDivider(color = colors.mushafLine.copy(alpha = .82f))

                Box(Modifier.fillMaxWidth().weight(1f)) {
                if (qpcMode) {
                    QpcV4MushafPage(
                        m = m,
                        page = data.page,
                        repository = repository,
                        settings = settings,
                        audio = audio,
                        onTip = { },
                        onAyahSelected = onAyahSelected,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (elements.isNotEmpty()) {
                            Column(Modifier.fillMaxSize()) {
                                elements.forEach { element ->
                                    when (element) {
                                        is MushafElement.Header -> MushafHeader(m, element, settings, audio)
                                        is MushafElement.Line -> MushafLineView(
                                            m = m,
                                            line = element,
                                            page = data.page,
                                            surahMap = surahMap,
                                            settings = settings,
                                            audio = audio,
                                            onTip = { },
                                            onAyahSelected = onAyahSelected
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "Загружаем локальный текст…",
                                modifier = Modifier.fillMaxWidth().padding(m.md),
                                textAlign = TextAlign.Center,
                                color = colors.mushafMuted,
                                fontSize = m.bodySmall
                            )
                        }
                    }
                } else if (elements.isNotEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        elements.forEach { element ->
                            when (element) {
                                is MushafElement.Header -> MushafHeader(m, element, settings, audio)
                                is MushafElement.Line -> MushafLineView(
                                    m = m,
                                    line = element,
                                    page = data.page,
                                    surahMap = surahMap,
                                    settings = settings,
                                    audio = audio,
                                    onTip = { },
                                    onAyahSelected = onAyahSelected
                                )
                            }
                        }
                    }
                }
            }

                HorizontalDivider(color = colors.mushafLine.copy(alpha = .72f))
                MushafPagePosition(m, data.page)
            }

        }
    }
}

@Composable
private fun MushafTopLine(m: AdaptiveMetrics, data: ReaderPayload) {
    val colors = LocalQuranColors.current
    val surahName = data.pageAyahs.firstOrNull()?.surahName.orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("القرآن الكريم", fontFamily = QuranFont, fontSize = m.bodySmall, color = colors.mushafMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Text(surahName, fontSize = m.bodySmall, fontWeight = FontWeight.SemiBold, color = colors.mushafMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text("الجزء ${data.juz}", fontFamily = QuranFont, fontSize = m.bodySmall, color = colors.mushafMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}


@Composable
private fun MushafPagePosition(m: AdaptiveMetrics, page: Int) {
    val colors = LocalQuranColors.current
    val sideMode = MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.BOTTOM ||
        MushafUiPrefs.pageNumberPlacement == MushafPageNumberPlacement.SIDES

    if (sideMode) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.sm)
        ) {
            Text(
                page.toString(),
                fontSize = m.heading,
                fontWeight = FontWeight.Medium,
                color = colors.mushafMuted,
                modifier = Modifier.align(if (page % 2 != 0) Alignment.CenterEnd else Alignment.CenterStart)
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(vertical = m.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (page % 2 == 0) Icon(
                Icons.Default.MenuBook,
                "Левая страница мусхафа",
                Modifier.size(m.icon),
                tint = colors.mushafInk
            )
            Text(
                page.toString(),
                fontSize = m.heading,
                fontWeight = FontWeight.Medium,
                color = colors.mushafMuted,
                modifier = Modifier.padding(horizontal = m.sm)
            )
            if (page % 2 != 0) Icon(
                Icons.Default.MenuBook,
                "Правая страница мусхафа",
                Modifier.size(m.icon),
                tint = colors.mushafInk
            )
        }
    }
}

private fun buildMushafElements(page:WordPage,geometry:List<MushafLineGeometry>,surahs:Map<Int,SurahData>):List<MushafElement>{
    val out=mutableListOf<MushafElement>();val shown=mutableSetOf<Int>()
    page.words.groupBy{it.line}.toSortedMap().forEach{(line,words)->
        words.firstOrNull{it.ayah==1&&it.position==1}?.let{w->if(shown.add(w.surah)){surahs[w.surah]?.let{s->out+=MushafElement.Header(s.id,s.nameRu,s.nameAr,if(s.id!=9&&s.id!=1)s.bismillah else null)}}}
        if(page.page==1 && shown.add(1)){surahs[1]?.let{s->out.add(0,MushafElement.Header(1,s.nameRu,s.nameAr,null))}}
        out+=MushafElement.Line(line,words,geometry.getOrNull(line-1))
    }
    return out
}

@Composable
private fun MushafHeader(m:AdaptiveMetrics,h:MushafElement.Header,settings:AppSettings,audio:QuranAudioController){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(m.xs)){
        val colors=LocalQuranColors.current
        Surface(shape=RoundedCornerShape(m.corner*.42f),color=colors.mushafHeader,border=BorderStroke(m.xs*.08f,colors.mushafLineStrong),modifier=Modifier.fillMaxWidth()){
            Row(Modifier.fillMaxWidth().padding(m.sm),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(h.nameAr,fontFamily=QuranFont,fontSize=m.heading,color=colors.mushafInk);Text("  ·  ${h.nameRu}",fontSize=m.bodySmall,color=colors.mushafMuted)}
        }
        h.bismillah?.let{b->Text(tajweedText(b.tw,settings.tajweed),fontFamily=QuranFont,fontSize=m.arabicBase*settings.arabicScale*.72f,textAlign=TextAlign.Center,color=LocalQuranColors.current.mushafInk,modifier=Modifier.fillMaxWidth())}
    }
}

@Composable
private fun MushafLineView(
    m: AdaptiveMetrics,
    line: MushafElement.Line,
    page: Int,
    surahMap: Map<Int, SurahData>,
    settings: AppSettings,
    audio: QuranAudioController,
    onTip: (String) -> Unit,
    onAyahSelected: (Int, Int) -> Unit
){
    val track by audio.track.collectAsState()
    val annotated=remember(line.words,settings.tajweed,settings.mushafWordSpacing,settings.mushafSpacingMode,track,surahMap){buildMushafLineAnnotated(line.words,surahMap,settings,track)}
    var layout by remember{mutableStateOf<TextLayoutResult?>(null)}
    var fit by remember(line.line,page){mutableFloatStateOf(1f)}
    val fraction=if(settings.mushafSpacingMode==MushafSpacingMode.ACADEMY) line.geometry?.width?:1f else 1f
    val align=when(line.geometry?.align){'r'->Alignment.CenterEnd;'l'->Alignment.CenterStart;else->Alignment.Center}
    Box(Modifier.fillMaxWidth(),contentAlignment=align){
        Box(Modifier.fillMaxWidth(fraction.coerceIn(.48f,1f))){
            BasicText(
                text=annotated,
                modifier=Modifier.fillMaxWidth().pointerInput(annotated,settings.mushafHoverMode){detectTapGestures{pos:Offset->
                    val lr=layout?:return@detectTapGestures;val offset=lr.getOffsetForPosition(pos);val end=(offset+1).coerceAtMost(annotated.length)
                    val wordTip=annotated.getStringAnnotations("wordTip",offset,end).firstOrNull()?.item
                    val ayahTip=annotated.getStringAnnotations("ayahTip",offset,end).firstOrNull()?.item
                    val marker=annotated.getStringAnnotations("marker",offset,end).isNotEmpty()
                    val tip=when(settings.mushafHoverMode){
                        MushafHoverMode.OFF->null
                        MushafHoverMode.WORD->if(marker)null else wordTip
                        MushafHoverMode.AYAH->ayahTip
                        MushafHoverMode.BOTH->if(marker)ayahTip else wordTip
                    }
                    tip?.takeIf{it.isNotBlank()}?.let(onTip)
                    annotated.getStringAnnotations("ayah",offset,end).firstOrNull()?.item?.let{k->
                        if(k!="bismillah"){
                            val ss=k.substringBefore(':').toIntOrNull()?:return@let
                            val a=k.substringAfter(':').toIntOrNull()?:return@let
                            onAyahSelected(ss,a)
                            audio.playAyah(ss,a,surahMap[ss]?.nameRu?:"Сура $ss")
                        } else {
                            val targetSurah = line.words.firstOrNull { it.surah != 1 || it.ayah != 1 }?.surah ?: 1
                            audio.playBismillah(targetSurah, surahMap[targetSurah]?.nameRu ?: "Сура $targetSurah")
                        }
                    }
                }},
                style=TextStyle(fontFamily=arabicFont(settings),fontSize=m.arabicBase*settings.arabicScale*.73f*fit,color=LocalQuranColors.current.mushafInk,textAlign=TextAlign.Center,lineHeight=m.arabicBase*settings.arabicScale*.9f*fit),
                maxLines=1,softWrap=false,overflow=TextOverflow.Clip,
                onTextLayout={r->layout=r;if(r.didOverflowWidth&&fit>.48f)fit=(fit*.94f).coerceAtLeast(.48f)}
            )
        }
    }
}

private fun buildMushafLineAnnotated(words:List<WordItem>,surahs:Map<Int,SurahData>,settings:AppSettings,track:Track?):AnnotatedString=buildAnnotatedString{
    val cache=mutableMapOf<String,List<AnnotatedString>>()
    fun styled(key:String,pos:Int,fallback:String):AnnotatedString{
        val list=cache.getOrPut(key){
            if(key=="bismillah") tajweedWords(surahs[1]?.bismillah?.tw.orEmpty(),settings.tajweed)
            else {val ss=key.substringBefore(':').toIntOrNull()?:0;val a=key.substringAfter(':').toIntOrNull()?:0;tajweedWords(surahs[ss]?.ayahs?.firstOrNull{it.a==a}?.tw.orEmpty(),settings.tajweed)}
        }
        return list.getOrNull(pos-1)?:AnnotatedString(fallback)
    }
    fun ayahTranslation(key:String):String{
        if(key=="bismillah") return "Басмала"
        val ss=key.substringBefore(':').toIntOrNull()?:return "";val a=key.substringAfter(':').toIntOrNull()?:return ""
        return surahs[ss]?.ayahs?.firstOrNull{it.a==a}?.tr.orEmpty()
    }
    val fixedSpaces = if(settings.mushafSpacingMode==MushafSpacingMode.FIXED) {
        val hairs=(settings.mushafWordSpacing*12f).toInt().coerceIn(0,4); "\u00A0"+"\u200A".repeat(hairs)
    } else "  "
    words.forEachIndexed{i,w->
        val mapped=appKeyForCanonicalWord(w);val key=mapped?.first?:"${w.surah}:${w.ayah}";val pos=mapped?.second?:w.position
        val start=length;append(styled(key,pos,w.arabic));val end=length
        addStringAnnotation("wordTip",w.translation,start,end);addStringAnnotation("ayahTip",ayahTranslation(key),start,end);addStringAnnotation("ayah",key,start,end)
        val active=track?.kind==TrackKind.AYAH&&track.surah==key.substringBefore(':').toIntOrNull()&&(key.substringAfter(':').toIntOrNull()?:-1) in track.ayah..track.endAyah
        if(active)addStyle(SpanStyle(textDecoration=TextDecoration.Underline),start,end)
        val nextKey=words.getOrNull(i+1)?.let{appKeyForCanonicalWord(it)?.first?:"${it.surah}:${it.ayah}"}
        if(key!="bismillah"&&key!=nextKey){
            val a=key.substringAfter(':').toIntOrNull()?:0;val ms=length;append(" ${toArabicDigits(a)}");addStringAnnotation("ayah",key,ms,length);addStringAnnotation("ayahTip",ayahTranslation(key),ms,length);addStringAnnotation("marker","1",ms,length)
        }
        if(i<words.lastIndex)append(fixedSpaces)
    }
}

private enum class ReaderSheetSection { AUDIO, TAFSIR, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomSheet(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    preferences: AppPreferences,
    settings: AppSettings,
    alaut: Map<String, AlautdinovEntry>,
    audio: QuranAudioController,
    navigator: AppNavigator,
    selectedSurah: Int,
    selectedAyah: Int,
    section: ReaderSheetSection,
    onSection: (ReaderSheetSection) -> Unit,
    onDismiss: () -> Unit,
    onModeChange: (ReadingMode) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .32f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(.88f),
                shape = RoundedCornerShape(
                    topStart = m.corner * 1.18f,
                    topEnd = m.corner * 1.18f,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                ),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = m.xs * .18f,
                shadowElevation = m.xs * .55f
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = m.md, vertical = m.sm),
                    verticalArrangement = Arrangement.spacedBy(m.sm)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Меню чтения",
                                fontSize = m.heading,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = m.xs)
                            )
                            Text(
                                when (data.mode) {
                                    ReadingMode.SURAH -> "Сура ${data.currentSurah} · страница ${data.page}"
                                    ReadingMode.PAGE -> "Страница ${data.page}"
                                    ReadingMode.MUSHAF -> "Мусхаф · страница ${data.page}"
                                },
                                fontSize = m.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = m.xs)
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(m.xs)
                    ) {
                        ReaderSheetSegment(
                            m = m,
                            label = "Аудио",
                            icon = Icons.Default.Headphones,
                            selected = section == ReaderSheetSection.AUDIO,
                            modifier = Modifier.weight(1f)
                        ) { onSection(ReaderSheetSection.AUDIO) }

                        ReaderSheetSegment(
                            m = m,
                            label = "Тафсир",
                            icon = Icons.Default.Article,
                            selected = section == ReaderSheetSection.TAFSIR,
                            modifier = Modifier.weight(1f)
                        ) { onSection(ReaderSheetSection.TAFSIR) }

                        ReaderSheetSegment(
                            m = m,
                            label = "Настройки",
                            icon = Icons.Default.Settings,
                            selected = section == ReaderSheetSection.SETTINGS,
                            modifier = Modifier.weight(1f)
                        ) { onSection(ReaderSheetSection.SETTINGS) }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))

                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (section) {
                            ReaderSheetSection.AUDIO -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(m.md)
                            ) {
                                ReaderAudioSheet(
                                    m = m,
                                    data = data,
                                    repository = repository,
                                    preferences = preferences,
                                    audio = audio,
                                    navigator = navigator,
                                    selectedSurah = selectedSurah,
                                    selectedAyah = selectedAyah
                                )
                                Spacer(Modifier.height(m.lg))
                            }

                            ReaderSheetSection.TAFSIR -> ReaderPageTafsirSheet(
                                m = m,
                                data = data,
                                repository = repository,
                                settings = settings,
                                alaut = alaut
                            )

                            ReaderSheetSection.SETTINGS -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(m.md)
                            ) {
                                ReaderSettingsSheet(
                                    m = m,
                                    preferences = preferences,
                                    settings = settings,
                                    onModeChange = onModeChange,
                                    onOpenAllSettings = {
                                        onDismiss()
                                        navigator.go(AppRoute.Settings)
                                    }
                                )
                                Spacer(Modifier.height(m.lg))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSheetSegment(
    m: AdaptiveMetrics,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(m.corner * .55f),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        border = BorderStroke(
            m.xs * .055f,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .35f)
            else MaterialTheme.colorScheme.outline.copy(alpha = .16f)
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = m.xs, vertical = m.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.xs * .55f)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(m.iconSmall),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                fontSize = m.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReaderAudioSheet(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    preferences: AppPreferences,
    audio: QuranAudioController,
    navigator: AppNavigator,
    selectedSurah: Int,
    selectedAyah: Int
) {
    val resolved by produceState<Pair<SurahData, Ayah>?>(null, selectedSurah, selectedAyah) {
        value = repository.resolve("$selectedSurah:$selectedAyah")
    }
    val pair = resolved
    val track by audio.track.collectAsState()
    val playing by audio.playing.collectAsState()
    val position by audio.position.collectAsState()
    val duration by audio.duration.collectAsState()
    val repeat by audio.repeat.collectAsState()
    val settings by preferences.settings.collectAsState()
    val error by audio.error.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val offlineStore = remember(appContext) { AudioOfflineStore(appContext) }
    var offlineMessage by remember(settings.reciter) { mutableStateOf<String?>(null) }
    val offlineCount by produceState(0, settings.reciter) {
        while (isActive) {
            value = runCatching { offlineStore.downloadedCount(settings.reciter) }.getOrDefault(0)
            delay(1_500L)
        }
    }

    val downloadRequired by audio.downloadRequired.collectAsState()
    var pendingNotificationDownload by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val reciterId = pendingNotificationDownload
        pendingNotificationDownload = null
        if (granted && reciterId != null) {
            AudioDownloadScheduler.enqueueReciter(appContext, reciterId)
            offlineMessage = "Скачивание запущено в фоне. Прогресс отображается в уведомлении."
            audio.dismissDownloadRequest()
        } else if (!granted) {
            offlineMessage = "Для фоновой загрузки с видимым прогрессом разрешите уведомления."
        }
    }

    fun startFullReciterDownload(reciterId: String) {
        if (Build.VERSION.SDK_INT <= 28) {
            offlineMessage = "На Android 8/9 требуется доступ к Downloads. Открываю раздел Аудио."
            navigator.go(AppRoute.Audio)
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationDownload = reciterId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        AudioDownloadScheduler.enqueueReciter(appContext, reciterId)
        offlineMessage = "Скачивание запущено в фоне. Можно продолжать читать Коран."
        audio.dismissDownloadRequest()
    }

    downloadRequired?.let { requiredId ->
        val requiredName = RECITERS[requiredId]?.name ?: requiredId
        AlertDialog(
            onDismissRequest = { audio.dismissDownloadRequest() },
            title = { Text("Аудио недоступно") },
            text = {
                Text(
                    "Не удалось получить аудио «$requiredName». Обычно достаточно проверить интернет и снова нажать на аят. " +
                        "Полный комплект чтеца можно скачать отдельно для полностью оффлайн-режима."
                )
            },
            confirmButton = {
                Button(onClick = { startFullReciterDownload(requiredId) }) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = { audio.dismissDownloadRequest() }) {
                    Text("Позже")
                }
            }
        )
    }

    val activeSelected = track?.kind == TrackKind.AYAH &&
        track?.surah == selectedSurah &&
        selectedAyah in (track?.ayah ?: -1)..(track?.endAyah ?: -1)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.md)) {
        Surface(
            shape = RoundedCornerShape(m.corner * .55f),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f)
        ) {
            Column(Modifier.fillMaxWidth().padding(m.md), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                Text(
                    pair?.first?.nameRu ?: "Сура $selectedSurah",
                    fontSize = m.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Аят $selectedAyah", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
            }
        }

        ReciterDropdown(m, preferences, Modifier.fillMaxWidth())

        Surface(
            shape = RoundedCornerShape(m.corner * .55f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(m.md),
                verticalArrangement = Arrangement.spacedBy(m.sm)
            ) {
                val reciterName = RECITERS[settings.reciter]?.name ?: settings.reciter
                Text("Аудио · GitHub + оффлайн-кэш", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                Text(
                    "$reciterName · $offlineCount / ${AudioOfflineStore.EXPECTED_AYAH_FILES} файлов",
                    fontSize = m.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                offlineMessage?.let {
                    Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(m.xs),
                    verticalArrangement = Arrangement.spacedBy(m.xs)
                ) {
                    Button(
                        onClick = { startFullReciterDownload(settings.reciter) },
                        enabled = offlineCount < AudioOfflineStore.EXPECTED_AYAH_FILES
                    ) {
                        Text(
                            when {
                                offlineCount == 0 -> "Скачать чтеца"
                                offlineCount < AudioOfflineStore.EXPECTED_AYAH_FILES -> "Продолжить загрузку"
                                else -> "Установлено"
                            },
                            fontSize = m.bodySmall
                        )
                    }

                    if (offlineCount > 0 && offlineCount < AudioOfflineStore.EXPECTED_AYAH_FILES) {
                        Button(onClick = {
                            AudioDownloadScheduler.cancel(appContext, settings.reciter)
                            offlineMessage = "Фоновая загрузка остановлена."
                        }) {
                            Text("Остановить", fontSize = m.bodySmall)
                        }
                    }

                    if (offlineCount > 0) {
                        Button(onClick = {
                            AudioDownloadScheduler.cancel(appContext, settings.reciter)
                            scope.launch {
                                offlineStore.deleteReciter(settings.reciter)
                                offlineMessage = "Локальные MP3 выбранного чтеца удалены."
                            }
                        }) {
                            Text("Удалить", fontSize = m.bodySmall)
                        }
                    }
                }
            }
        }

        error?.let {
            Surface(shape = RoundedCornerShape(m.corner * .45f), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(m.sm),
                    fontSize = m.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.sm)
        ) {
            Text(formatTime(position), fontSize = m.bodySmall)
            Slider(
                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { audio.seekTo(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(formatTime(duration), fontSize = m.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { scope.launch { audio.advance(-1) } },
                modifier = Modifier.size(m.touch)
            ) { Icon(Icons.Default.SkipPrevious, "Предыдущий", Modifier.size(m.iconSmall)) }

            IconButton(
                onClick = { audio.seekTo((position - 10_000L).coerceAtLeast(0L)) },
                modifier = Modifier.size(m.touch)
            ) { Icon(Icons.Default.FastRewind, "−10 секунд", Modifier.size(m.iconSmall)) }

            Surface(
                onClick = {
                    if (activeSelected) audio.toggle()
                    else audio.playAyah(
                        selectedSurah,
                        selectedAyah,
                        pair?.first?.nameRu ?: "Сура $selectedSurah"
                    )
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(m.touch * 1.22f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing && activeSelected) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Воспроизвести",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(m.icon)
                    )
                }
            }

            IconButton(
                onClick = { audio.seekTo((position + 10_000L).coerceAtMost(duration.coerceAtLeast(position + 10_000L))) },
                modifier = Modifier.size(m.touch)
            ) { Icon(Icons.Default.FastForward, "+10 секунд", Modifier.size(m.iconSmall)) }

            IconButton(
                onClick = { scope.launch { audio.advance(1) } },
                modifier = Modifier.size(m.touch)
            ) { Icon(Icons.Default.SkipNext, "Следующий", Modifier.size(m.iconSmall)) }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(m.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = repeat,
                onClick = { audio.setRepeat(!repeat) },
                label = { Text("Повтор", fontSize = m.bodySmall) },
                leadingIcon = { Icon(Icons.Default.RepeatOne, null, Modifier.size(m.iconSmall * .82f)) }
            )
            FilterChip(
                selected = settings.autoAdvance,
                onClick = { preferences.updateSettings { it.copy(autoAdvance = !it.autoAdvance) } },
                label = { Text("Следующий аят", fontSize = m.bodySmall) }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        Text("Мини-плеер", fontSize = m.body, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.xs)) {
            ReaderModeChoice(
                m,
                "Открыт",
                MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.EXPANDED,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.expand() }
            ReaderModeChoice(
                m,
                "Кнопка",
                MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.COLLAPSED,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.collapse() }
            ReaderModeChoice(
                m,
                "Внизу",
                MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.BOTTOM,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.dockBottom() }
            ReaderModeChoice(
                m,
                "Тулбар",
                MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.TOOLBAR,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.moveToToolbar() }
        }
        Text("Размер плавающей кнопки", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.xs)) {
            ReaderModeChoice(
                m,
                "Мини",
                MiniPlayerUiPrefs.fabSize == MiniPlayerFabSize.MINI,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.fabSize = MiniPlayerFabSize.MINI }
            ReaderModeChoice(
                m,
                "Большая",
                MiniPlayerUiPrefs.fabSize == MiniPlayerFabSize.LARGE,
                Modifier.weight(1f)
            ) { MiniPlayerUiPrefs.fabSize = MiniPlayerFabSize.LARGE }
        }
        ReaderSheetSwitch(
            m,
            "Поднимать для нижних аятов",
            "В открытом виде плеер автоматически уходит вверх, если воспроизводится нижний аят страницы.",
            MiniPlayerUiPrefs.autoLiftForBottomAyah
        ) { MiniPlayerUiPrefs.autoLiftForBottomAyah = it }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        Text("Хадр · Коран за 7 часов", fontSize = m.body, fontWeight = FontWeight.SemiBold)
        HadrPlayer(m, data.page, audio, navigator)
    }
}

@Composable
private fun ReaderPageTafsirSheet(
    m: AdaptiveMetrics,
    data: ReaderPayload,
    repository: QuranRepository,
    settings: AppSettings,
    alaut: Map<String, AlautdinovEntry>
) {
    val pageAyahs by produceState<List<PageAyah>>(emptyList(), data.page) {
        value = runCatching { repository.page(data.page) }.getOrDefault(emptyList())
    }

    if (pageAyahs.isEmpty()) {
        LoadingPane(m, "Открываем перевод и тафсир страницы…")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = m.lg),
        verticalArrangement = Arrangement.spacedBy(m.md)
    ) {
        item {
            Text(
                "Страница ${data.page} · ${pageAyahs.size} ${pluralizeAyah(pageAyahs.size)}",
                fontSize = m.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(pageAyahs, key = { "${it.surah}:${it.a}" }) { row ->
            val ayah = row.ayah
            val key = "${row.surah}:${row.a}"
            val extra = alaut[key]

            Surface(
                shape = RoundedCornerShape(m.corner * .58f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f),
                border = BorderStroke(m.xs * .045f, MaterialTheme.colorScheme.outline.copy(alpha = .12f))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(m.md),
                    verticalArrangement = Arrangement.spacedBy(m.sm)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            row.surahName,
                            fontSize = m.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Аят ${row.a}", fontSize = m.bodySmall, fontWeight = FontWeight.SemiBold)
                    }

                    if (settings.readerShowArabic) {
                        if (settings.tajweed || settings.arabicFont == "qpc-v4") {
                            QpcV4AyahText(
                                m = m,
                                surah = row.surah,
                                ayah = row.a,
                                page = ayah.p,
                                settings = settings,
                                fallback = ayah.ar
                            )
                        } else {
                            Text(
                                ayah.ar,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                fontFamily = arabicFont(settings),
                                fontSize = m.arabicBase * settings.arabicScale,
                                lineHeight = (m.arabicBase * settings.arabicScale) * settings.lineHeight
                            )
                        }
                    }

                    Text(
                        "Перевод",
                        fontSize = m.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (ayah.tr.isNotBlank()) {
                        TextContentBlock(m, "Перевод · Azan.ru", ayah.tr, m.body * settings.translationScale)
                    } else {
                        Text(
                            "Перевод для этого аята не найден.",
                            fontSize = m.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!extra?.translation.isNullOrBlank()) {
                        TextContentBlock(
                            m,
                            "Перевод · Шамиль Аляутдинов",
                            extra!!.translation,
                            m.body * settings.translationScale
                        )
                    }

                    if (settings.readerShowTafsir) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .14f))
                        Text(
                            "Тафсир",
                            fontSize = m.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (ayah.tf.isNotBlank() || !ayah.tfBlocks.isNullOrEmpty()) {
                            TafsirArticle(m, ayah.tfBlocks, ayah.tf, settings, scopeKey = key)
                        }
                        if (!extra?.tafsir.isNullOrBlank()) {
                            TextContentBlock(
                                m,
                                "Тафсир · Шамиль Аляутдинов",
                                extra!!.tafsir,
                                m.body * settings.tafsirScale
                            )
                        }
                        if (ayah.tf.isBlank() && ayah.tfBlocks.isNullOrEmpty() && extra?.tafsir.isNullOrBlank()) {
                            Text(
                                "Тафсир для этого аята не найден.",
                                fontSize = m.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    m: AdaptiveMetrics,
    preferences: AppPreferences,
    settings: AppSettings,
    onModeChange: (ReadingMode) -> Unit,
    onOpenAllSettings: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.md)) {
        Text("Режим чтения", fontSize = m.body, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.xs)) {
            ReaderModeChoice(m, "Сура", settings.readingMode == ReadingMode.SURAH, Modifier.weight(1f)) { onModeChange(ReadingMode.SURAH) }
            ReaderModeChoice(m, "Страница", settings.readingMode == ReadingMode.PAGE, Modifier.weight(1f)) { onModeChange(ReadingMode.PAGE) }
            ReaderModeChoice(m, "Мусхаф", settings.readingMode == ReadingMode.MUSHAF, Modifier.weight(1f)) { onModeChange(ReadingMode.MUSHAF) }
        }
        Text(
            "Последний выбранный режим запоминается и используется для переходов из меню, закладок и навигации.",
            fontSize = m.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        ReaderSheetSwitch(m, "Аудио", "Показывать пункт аудио в меню чтения.", settings.readerShowAudio) {
            preferences.updateSettings { x -> x.copy(readerShowAudio = it) }
        }
        ReaderSheetSwitch(m, "Арабский текст", "Показывать арабский текст внутри панели перевода и тафсира.", settings.readerShowArabic) {
            preferences.updateSettings { x -> x.copy(readerShowArabic = it) }
        }
        ReaderSheetSwitch(m, "Тафсир", "Перевод показывается всегда.", settings.readerShowTafsir) {
            preferences.updateSettings { x -> x.copy(readerShowTafsir = it) }
        }
        ReaderSheetSwitch(m, "Продолжать следующим аятом", null, settings.autoAdvance) {
            preferences.updateSettings { x -> x.copy(autoAdvance = it) }
        }
        ReaderSheetSwitch(m, "Таджвид", null, settings.tajweed) {
            preferences.updateSettings { x -> x.copy(tajweed = it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        Text("Положение номера страницы", fontSize = m.body, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.xs)) {
            ReaderModeChoice(
                m,
                "По центру",
                MushafUiPrefs.pageNumberPlacement == MushafPageNumberPlacement.CENTER,
                Modifier.weight(1f)
            ) { MushafUiPrefs.pageNumberPlacement = MushafPageNumberPlacement.CENTER }
            ReaderModeChoice(
                m,
                "По краям",
                MushafUiPrefs.pageNumberPlacement == MushafPageNumberPlacement.SIDES,
                Modifier.weight(1f)
            ) { MushafUiPrefs.pageNumberPlacement = MushafPageNumberPlacement.SIDES }
        }
        Button(onClick = onOpenAllSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть все настройки", fontSize = m.bodySmall)
        }
    }
}

@Composable
private fun ReaderModeChoice(
    m: AdaptiveMetrics,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(m.corner * .50f),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = m.xs, vertical = m.sm), contentAlignment = Alignment.Center) {
            Text(label, fontSize = m.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun ReaderSheetSwitch(
    m: AdaptiveMetrics,
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.xs)) {
            Text(label, fontSize = m.body, fontWeight = FontWeight.SemiBold)
            hint?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HadrPlayer(m: AdaptiveMetrics, page: Int, audio: QuranAudioController, navigator: AppNavigator) {
    val playing by audio.hadrPlaying.collectAsState()
    val position by audio.hadrPosition.collectAsState()
    val duration by audio.hadrDuration.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(m.md)) {
        Column(Modifier.fillMaxWidth()) {
            Text("Ахмад Дибан · Хадр", fontSize = m.body, fontWeight = FontWeight.SemiBold)
            Text(
                "7:28:33 · непрерывный глобальный плеер. Автоперелистывание временно отключено.",
                fontSize = m.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val maxDuration = duration.takeIf { it > 0 } ?: 26_913_000L
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.sm)) {
            Text(formatTime(position), fontSize = m.bodySmall)
            Slider(
                value = position.coerceAtMost(maxDuration).toFloat(),
                onValueChange = { audio.seekHadr(it.toLong()) },
                valueRange = 0f..maxDuration.toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(formatTime(maxDuration), fontSize = m.bodySmall)
        }
        Button(onClick = audio::toggleHadr) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(m.iconSmall))
            Text(if (playing) "Пауза" else "Слушать", fontSize = m.bodySmall, modifier = Modifier.padding(start = m.sm))
        }
    }
}

private fun toArabicDigits(n:Int):String=n.toString().map{if(it.isDigit())("٠١٢٣٤٥٦٧٨٩"[it-'0'])else it}.joinToString("")
