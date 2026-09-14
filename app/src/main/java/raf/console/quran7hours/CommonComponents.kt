package raf.quran7hours.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LoadingPane(m: AdaptiveMetrics, label: String = "Загрузка…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.md)) {
            CircularProgressIndicator(modifier = Modifier.size(m.lg))
            Text(label, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyPane(m: AdaptiveMetrics, title: String, text: String = "") {
    Box(Modifier.fillMaxSize().padding(m.pagePadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.md)) {
            Text(title, fontSize = m.heading, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(text, fontSize = m.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PageHeading(m: AdaptiveMetrics, title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(m.sm)) {
        Text("КОРАН ЗА 7 ЧАСОВ", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, fontSize = m.title, lineHeight = m.title * 1.08f, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = m.body, lineHeight = m.body * 1.45f, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class MiniPlayerPresentation { EXPANDED, COLLAPSED, TOOLBAR, BOTTOM }
enum class MiniPlayerFabSize { MINI, LARGE }
enum class MushafPageNumberPlacement { CENTER, SIDES }

object MiniPlayerUiPrefs {
    // Playback is independent from presentation. TOOLBAR hides the floating
    // player and exposes a Play button in the app toolbar instead. BOTTOM docks
    // a compact transport into the Mushaf footer area.
    var presentation by mutableStateOf(MiniPlayerPresentation.EXPANDED)
    var fabSize by mutableStateOf(MiniPlayerFabSize.MINI)
    var autoLiftForBottomAyah by mutableStateOf(true)

    fun expand() { presentation = MiniPlayerPresentation.EXPANDED }
    fun collapse() { presentation = MiniPlayerPresentation.COLLAPSED }
    fun moveToToolbar() { presentation = MiniPlayerPresentation.TOOLBAR }
    fun dockBottom() { presentation = MiniPlayerPresentation.BOTTOM }
}

object MushafUiPrefs {
    var pageNumberPlacement by mutableStateOf(MushafPageNumberPlacement.CENTER)
    var showEdgePositionIndicator by mutableStateOf(false)
}

@Composable
fun MiniPlayer(
    m: AdaptiveMetrics,
    audio: QuranAudioController,
    repository: QuranRepository,
    preferences: AppPreferences,
    coordinate: ReaderCoordinate,
    modifier: Modifier = Modifier
) {
    val track by audio.track.collectAsState()
    val playing by audio.playing.collectAsState()
    val position by audio.position.collectAsState()
    val duration by audio.duration.collectAsState()
    val repeat by audio.repeat.collectAsState()
    val settings by preferences.settings.collectAsState()
    val hadrVisible by audio.hadrVisible.collectAsState()
    val hadrPlaying by audio.hadrPlaying.collectAsState()
    val hadrPosition by audio.hadrPosition.collectAsState()
    val hadrDuration by audio.hadrDuration.collectAsState()
    val scope = rememberCoroutineScope()
    if ((track == null && !hadrVisible) || MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.TOOLBAR) return

    val pageAyahs by produceState<List<PageAyah>>(emptyList(), coordinate.page) {
        value = withContext(Dispatchers.IO) {
            runCatching { repository.page(coordinate.page) }.getOrDefault(emptyList())
        }
    }
    val currentTrack = track
    val lowerAyah = remember(pageAyahs, currentTrack) {
        if (currentTrack?.kind != TrackKind.AYAH || pageAyahs.isEmpty()) false
        else {
            val index = pageAyahs.indexOfFirst {
                it.surah == currentTrack.surah &&
                        it.a in currentTrack.ayah..currentTrack.endAyah
            }
            val lowerZoneStart = (pageAyahs.size * .68f).toInt().coerceAtLeast(0)
            index >= lowerZoneStart && index >= 0
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxTravelYPx = with(density) {
            (maxHeight - m.touch * 2.35f).coerceAtLeast(m.touch).toPx()
        }
        val collapsedButtonSize = if (MiniPlayerUiPrefs.fabSize == MiniPlayerFabSize.LARGE) m.touch * 1.08f else m.touch * .68f
        val maxFloatingTravelYPx = with(density) {
            (maxHeight - collapsedButtonSize).coerceAtLeast(m.touch).toPx()
        }
        val maxTravelXPx = with(density) {
            (maxWidth / 2f - collapsedButtonSize / 2f).coerceAtLeast(m.touch * .25f).toPx()
        }
        val smallAnchorXPx = with(density) { (m.touch * .92f).toPx() }
        val smallAnchorYPx = with(density) { -(m.xs * .20f).toPx() }
        val largeAnchorXPx = maxTravelXPx * .82f
        val largeAnchorYPx = with(density) { -(m.sm * .55f).toPx() }
        var verticalOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }
        var floatingOffsetXPx by rememberSaveable { mutableFloatStateOf(0f) }
        var floatingOffsetYPx by rememberSaveable { mutableFloatStateOf(0f) }
        var manuallyMoved by remember(currentTrack?.kind, currentTrack?.surah, currentTrack?.ayah, currentTrack?.endAyah, hadrVisible) {
            mutableStateOf(false)
        }

        val collapsed = MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.COLLAPSED

        LaunchedEffect(lowerAyah, MiniPlayerUiPrefs.autoLiftForBottomAyah, currentTrack?.surah, currentTrack?.ayah, collapsed) {
            if (!manuallyMoved && !collapsed) {
                verticalOffsetPx = if (lowerAyah && MiniPlayerUiPrefs.autoLiftForBottomAyah) {
                    -maxTravelYPx * .78f
                } else {
                    0f
                }
            }
        }

        val verticalDragModifier = Modifier.pointerInput(maxTravelYPx, currentTrack?.surah, currentTrack?.ayah, collapsed) {
            detectVerticalDragGestures { change, dragAmount ->
                change.consume()
                manuallyMoved = true
                verticalOffsetPx = (verticalOffsetPx + dragAmount).coerceIn(-maxTravelYPx, 0f)
            }
        }

        if (collapsed) {
            val large = MiniPlayerUiPrefs.fabSize == MiniPlayerFabSize.LARGE
            val baseX = if (large) largeAnchorXPx else smallAnchorXPx
            val baseY = if (large) largeAnchorYPx else smallAnchorYPx
            val freeDragModifier = Modifier.pointerInput(maxTravelXPx, maxFloatingTravelYPx, large) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val absoluteX = (baseX + floatingOffsetXPx + dragAmount.x).coerceIn(-maxTravelXPx, maxTravelXPx)
                    val absoluteY = (baseY + floatingOffsetYPx + dragAmount.y).coerceIn(-maxFloatingTravelYPx, 0f)
                    floatingOffsetXPx = absoluteX - baseX
                    floatingOffsetYPx = absoluteY - baseY
                }
            }
            Surface(
                onClick = { MiniPlayerUiPrefs.expand() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset((baseX + floatingOffsetXPx).roundToInt(), (baseY + floatingOffsetYPx).roundToInt()) }
                    .then(freeDragModifier)
                    .size(collapsedButtonSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = m.unit,
                shadowElevation = m.unit * 1.3f
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing || hadrPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Развернуть плеер",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(if (large) m.icon else m.iconSmall * .88f)
                    )
                }
            }
            return@BoxWithConstraints
        }

        if (MiniPlayerUiPrefs.presentation == MiniPlayerPresentation.BOTTOM) {
            val t = track

            // Footer mode is intentionally only as tall as the Mushaf footer itself.
            // It no longer rises over the last Quran line.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Небольшой адаптивный подъём ставит транспорт ровно по
                    // вертикальному центру нижней строки мусхафа/номера страницы.
                    // Значение зависит от AdaptiveMetrics, фиксированных px здесь нет.
                    .offset(y = -(m.xs * .32f))
                    .fillMaxWidth(.72f)
                    .height(m.touch * .94f),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = m.xs * .55f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.xs)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(m.touch * .68f),
                        onClick = { if (t != null) audio.toggle() else audio.toggleHadr() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if ((t != null && playing) || (t == null && hadrPlaying)) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Воспроизведение",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(m.iconSmall * .84f)
                            )
                        }
                    }

                    val current = if (t != null) position else hadrPosition
                    val total = if (t != null) duration else hadrDuration
                    val seekMax = total.coerceAtLeast(1L)

                    QuranFooterSeekBar(
                        m = m,
                        value = current.coerceIn(0L, seekMax),
                        maxValue = seekMax,
                        onSeek = { value ->
                            if (t != null) audio.seekTo(value)
                            else audio.seekHadr(value)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    if (t != null) {
                        val modeLabel = when {
                            repeat -> "Повтор одного аята"
                            settings.autoAdvance -> "Идти дальше"
                            else -> "Остановиться после аята"
                        }
                        IconButton(
                            onClick = {
                                when {
                                    repeat -> {
                                        audio.setRepeat(false)
                                        preferences.updateSettings { it.copy(autoAdvance = true) }
                                    }
                                    settings.autoAdvance -> {
                                        audio.setRepeat(false)
                                        preferences.updateSettings { it.copy(autoAdvance = false) }
                                    }
                                    else -> {
                                        audio.setRepeat(true)
                                        preferences.updateSettings { it.copy(autoAdvance = false) }
                                    }
                                }
                            },
                            modifier = Modifier.size(m.touch * .64f)
                        ) {
                            Icon(
                                when {
                                    repeat -> Icons.Default.Repeat
                                    settings.autoAdvance -> Icons.Default.SkipNext
                                    else -> Icons.Default.Close
                                },
                                contentDescription = modeLabel,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(m.iconSmall)
                            )
                        }

                        FooterReciterDropdown(
                            m = m,
                            preferences = preferences,
                            audio = audio
                        )
                    }
                }
            }
            return@BoxWithConstraints
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, verticalOffsetPx.roundToInt()) }
                .then(verticalDragModifier)
                .fillMaxWidth()
        ) {
            if (hadrVisible) {
                Surface(tonalElevation = m.unit * 1.3f, shadowElevation = m.unit * .9f) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.sm),
                        verticalArrangement = Arrangement.spacedBy(m.xs)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                            IconButton(onClick = audio::closeHadr, modifier = Modifier.size(m.touch * .78f)) {
                                Icon(Icons.Default.Close, "Закрыть Хадр", modifier = Modifier.size(m.iconSmall))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Коран за 7 часов", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Ахмад Дибан · Хадр", fontSize = m.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { MiniPlayerUiPrefs.moveToToolbar() }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.VisibilityOff, "В верхнюю панель", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { MiniPlayerUiPrefs.dockBottom() }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.VerticalAlignBottom, "В нижнюю панель", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { MiniPlayerUiPrefs.collapse() }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.KeyboardArrowDown, "Свернуть", modifier = Modifier.size(m.iconSmall))
                            }
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(m.touch * .88f), onClick = audio::toggleHadr) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (hadrPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Хадр", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(m.icon))
                                }
                            }
                        }
                        val hadrMax = hadrDuration.takeIf { it > 0 } ?: 26_913_000L
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                            Text(formatTime(hadrPosition), fontSize = m.bodySmall)
                            Slider(
                                value = hadrPosition.coerceAtMost(hadrMax).toFloat(),
                                onValueChange = { audio.seekHadr(it.toLong()) },
                                valueRange = 0f..hadrMax.toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(formatTime(hadrMax), fontSize = m.bodySmall)
                        }
                    }
                }
            }

            track?.let { t ->
                val label = when {
                    t.kind == TrackKind.BISMILLAH -> "Басмала"
                    t.endAyah > t.ayah -> "Аяты ${t.ayah}–${t.endAyah}"
                    else -> "Аят ${t.ayah}"
                }
                Surface(tonalElevation = m.unit * 1.2f, shadowElevation = m.unit * .8f) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.sm), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                            IconButton(onClick = audio::close, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.size(m.iconSmall))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(label, fontSize = m.body, fontWeight = FontWeight.SemiBold)
                                ReciterDropdown(m, preferences, compact = true)
                            }
                            IconButton(
                                onClick = { MiniPlayerUiPrefs.moveToToolbar() },
                                modifier = Modifier.size(m.touch * .72f)
                            ) {
                                Icon(Icons.Default.VisibilityOff, "Скрыть мини-плеер", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { MiniPlayerUiPrefs.dockBottom() }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.VerticalAlignBottom, "В нижнюю панель", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { MiniPlayerUiPrefs.collapse() }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.KeyboardArrowDown, "Свернуть", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { scope.launch { audio.advance(-1) } }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.SkipPrevious, "Предыдущий", modifier = Modifier.size(m.iconSmall))
                            }
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(m.touch * .84f), onClick = audio::toggle) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(m.icon))
                                }
                            }
                            IconButton(onClick = { scope.launch { audio.advance(1) } }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.SkipNext, "Следующий", modifier = Modifier.size(m.iconSmall))
                            }
                            IconButton(onClick = { audio.setRepeat(!repeat) }, modifier = Modifier.size(m.touch * .72f)) {
                                Icon(Icons.Default.Repeat, "Повтор", tint = if (repeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(m.iconSmall))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                            Text(formatTime(position), fontSize = m.bodySmall)
                            Slider(
                                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                                onValueChange = { audio.seekTo(it.toLong()) },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(formatTime(duration), fontSize = m.bodySmall)
                            FilterChip(
                                selected = settings.autoAdvance,
                                onClick = { preferences.updateSettings { it.copy(autoAdvance = !it.autoAdvance) } },
                                label = { Text("Далее", fontSize = m.bodySmall) }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun QuranFooterSeekBar(
    m: AdaptiveMetrics,
    value: Long,
    maxValue: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.primary.copy(alpha = .20f)
    // Толщина и бегунок масштабируются от AdaptiveMetrics текущего устройства.
    // Полоса заметнее, но остаётся достаточно тонкой для нижней строки мусхафа.
    val trackWidthPx = with(density) {
        (m.unit * .52f).toPx()
    }

    val thumbRadiusPx = with(density) {
        (m.unit * 1.02f).toPx()
    }

    Canvas(
        modifier = modifier
            .height(m.touch * .64f)
            .pointerInput(maxValue) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    fun seekAt(x: Float) {
                        val left = thumbRadiusPx
                        val right = (size.width.toFloat() - thumbRadiusPx).coerceAtLeast(left + 1f)
                        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        onSeek((fraction * maxValue.toDouble()).toLong())
                    }

                    seekAt(down.position.x)
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                seekAt(change.position.x)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val max = maxValue.coerceAtLeast(1L)
        val fraction = (value.coerceIn(0L, max).toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val left = thumbRadiusPx
        val right = (size.width - thumbRadiusPx).coerceAtLeast(left + 1f)
        val y = size.height / 2f
        val thumbX = left + (right - left) * fraction

        drawLine(
            color = inactive,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )
        drawLine(
            color = active,
            start = Offset(left, y),
            end = Offset(thumbX, y),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = active,
            radius = thumbRadiusPx,
            center = Offset(thumbX, y)
        )
    }
}



@Composable
private fun FooterReciterDropdown(
    m: AdaptiveMetrics,
    preferences: AppPreferences,
    audio: QuranAudioController
) {
    val settings by preferences.settings.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val current = RECITERS[settings.reciter] ?: RECITERS.getValue("alafasy")

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(m.touch * .64f)
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = "Выбрать чтеца: ${current.name}",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(m.iconSmall)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            RECITERS.forEach { (id, reciter) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            reciter.name,
                            fontSize = m.bodySmall,
                            fontWeight = if (id == settings.reciter) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        preferences.updateSettings { it.copy(reciter = id) }
                        expanded = false
                        audio.reloadForReciter()
                    }
                )
            }
        }
    }
}

@Composable
fun ReciterDropdown(m:AdaptiveMetrics,preferences:AppPreferences,modifier:Modifier=Modifier,compact:Boolean=false){
    val settings by preferences.settings.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val current=RECITERS[settings.reciter] ?: RECITERS.getValue("alafasy")
    Box(modifier){
        OutlinedButton(
            onClick={expanded=true},
            modifier=if(compact)Modifier else Modifier.fillMaxWidth(),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(horizontal=m.sm,vertical=m.xs)
        ){
            Text(current.name,fontSize=if(compact)m.bodySmall*.84f else m.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=if(compact)Modifier else Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown,"Выбрать чтеца",Modifier.size(m.iconSmall*.72f))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            RECITERS.forEach{(id,r)->
                DropdownMenuItem(
                    text={Text(r.name,fontSize=m.bodySmall,fontWeight=if(id==settings.reciter)FontWeight.SemiBold else FontWeight.Normal)},
                    onClick={preferences.updateSettings{it.copy(reciter=id)};expanded=false}
                )
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

@Composable
fun NavigationDialog(
    m: AdaptiveMetrics,
    repository: QuranRepository,
    current: ReaderCoordinate,
    readingMode: ReadingMode,
    onDismiss: () -> Unit,
    onNavigate: (AppRoute) -> Unit
) {
    val meta by produceState<QuranMeta?>(null) { value = repository.meta() }
    var tab by remember { mutableIntStateOf(0) }
    var surahText by remember { mutableStateOf(current.surah.toString()) }
    var ayahText by remember { mutableStateOf(current.ayah.toString()) }
    var pageText by remember { mutableStateOf(current.page.toString()) }
    var juzText by remember { mutableStateOf(current.juz.toString()) }
    var query by remember { mutableStateOf("") }
    val targetSurah = surahText.toIntOrNull()?.coerceIn(1, 114) ?: 1
    val maxAyah = meta?.surahs?.getOrNull(targetSurah - 1)?.ayahs ?: 286
    val targetAyah = ayahText.toIntOrNull()?.coerceIn(1, maxAyah) ?: 1
    val targetPage = pageText.toIntOrNull()?.coerceIn(1, 604) ?: 1
    val targetJuz = juzText.toIntOrNull()?.coerceIn(1, 30) ?: 1

    val targetAyahPage by produceState(current.page, targetSurah, targetAyah) {
        value = runCatching {
            repository.surah(targetSurah).ayahs.firstOrNull { it.a == targetAyah }?.p
        }.getOrNull() ?: current.page
    }
    val pageAnchor by produceState<PageAyah?>(null, targetPage) {
        value = runCatching { repository.page(targetPage).firstOrNull() }.getOrNull()
    }
    val juzStartPages by produceState<Map<Int, Int>>(emptyMap(), meta) {
        val out = linkedMapOf<Int, Int>()
        meta?.juz?.forEach { j ->
            out[j.id] = runCatching { repository.resolve("${j.start.surah}:${j.start.ayah}")?.second?.p }.getOrNull()
                ?: meta?.surahs?.getOrNull(j.start.surah - 1)?.pageStart
                        ?: current.page
        }
        value = out
    }

    fun routeFor(surah: Int, ayah: Int, page: Int) = readingRoute(readingMode, surah, ayah, page)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(if (m.isLandscape) .92f else .82f),
            shape = RoundedCornerShape(m.corner), tonalElevation = m.unit
        ) {
            Column(Modifier.fillMaxSize().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Быстрый переход", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("Навигация по Корану", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Откроется режим: ${when (readingMode) { ReadingMode.SURAH -> "Сура"; ReadingMode.PAGE -> "Страница"; ReadingMode.MUSHAF -> "Мусхаф" }}",
                            fontSize = m.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.size(m.icon)) }
                }
                CoordinateStrip(m, "Сейчас", current)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.sm), verticalArrangement = Arrangement.spacedBy(m.sm)) {
                    listOf("Сура · аят", "Страница", "Джуз").forEachIndexed { i, label -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label, fontSize = m.bodySmall) }) }
                }
                when (tab) {
                    0 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(m.sm), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = surahText, onValueChange = { surahText = it.filter(Char::isDigit).take(3); ayahText = "1" }, label = { Text("Сура") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            OutlinedTextField(value = ayahText, onValueChange = { ayahText = it.filter(Char::isDigit).take(3) }, label = { Text("Аят") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            Button(onClick = { onNavigate(routeFor(targetSurah, targetAyah, targetAyahPage)) }, modifier = Modifier.weight(1f)) { Text("Перейти", fontSize = m.bodySmall) }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Название или номер суры") }, modifier = Modifier.fillMaxWidth())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                            meta?.surahs?.filter { "${it.id} ${it.nameRu} ${it.meaning} ${it.nameAr}".contains(query, ignoreCase = true) }?.forEach { sm ->
                                Surface(onClick = { onNavigate(routeFor(sm.id, 1, sm.pageStart)) }, shape = RoundedCornerShape(m.corner * .6f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f)) {
                                    Row(Modifier.fillMaxWidth().padding(m.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.md)) {
                                        Text(sm.id.toString(), fontSize = m.body, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Column(Modifier.weight(1f)) { Text(sm.nameRu, fontSize = m.body, fontWeight = FontWeight.SemiBold); Text("${sm.meaning} · ${sm.ayahs} ${pluralizeAyah(sm.ayahs)}", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        Text(sm.nameAr, fontFamily = QuranFont, fontSize = m.body * 1.15f)
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        OutlinedTextField(value = pageText, onValueChange = { pageText = it.filter(Char::isDigit).take(3) }, label = { Text("Страница 1–604") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        val anchor = pageAnchor
                        Button(
                            onClick = { onNavigate(routeFor(anchor?.surah ?: current.surah, anchor?.a ?: current.ayah, targetPage)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Открыть страницу", fontSize = m.body) }
                    }
                    else -> {
                        OutlinedTextField(value = juzText, onValueChange = { juzText = it.filter(Char::isDigit).take(2) }, label = { Text("Джуз 1–30") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        val item = meta?.juz?.getOrNull(targetJuz - 1)
                        Text(item?.let { "Начало: ${it.start.surah}:${it.start.ayah} · Конец: ${it.end.surah}:${it.end.ayah}" } ?: "", fontSize = m.body)
                        Button(
                            onClick = {
                                item?.let { j ->
                                    val page = juzStartPages[j.id] ?: current.page
                                    onNavigate(routeFor(j.start.surah, j.start.ayah, page))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = item != null
                        ) { Text("Открыть джуз", fontSize = m.body) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.sm), verticalArrangement = Arrangement.spacedBy(m.sm)) {
                            meta?.juz?.forEach { j ->
                                FilterChip(
                                    selected = j.id == targetJuz,
                                    onClick = {
                                        juzText = j.id.toString()
                                        val page = juzStartPages[j.id] ?: current.page
                                        onNavigate(routeFor(j.start.surah, j.start.ayah, page))
                                    },
                                    label = { Text(j.id.toString(), fontSize = m.bodySmall) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateStrip(m: AdaptiveMetrics, label: String, c: ReaderCoordinate) {
    Surface(shape = RoundedCornerShape(m.corner * .7f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
        Column(Modifier.fillMaxWidth().padding(m.md), verticalArrangement = Arrangement.spacedBy(m.xs)) {
            Text(label, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CoordinateValue(m, "${c.surah}:${c.ayah}", "сура · аят")
                CoordinateValue(m, c.page.toString(), "страница")
                CoordinateValue(m, c.juz.toString(), "джуз")
                CoordinateValue(m, c.range, "аяты")
            }
        }
    }
}

@Composable
private fun CoordinateValue(m: AdaptiveMetrics, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = m.body, fontWeight = FontWeight.Bold)
        Text(label, fontSize = m.bodySmall * .88f, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
