package raf.quran7hours.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class AudioMode { AYAH, HADR, CUSTOM }

@Composable
fun AudioScreen(
    m: AdaptiveMetrics,
    repository: QuranRepository,
    preferences: AppPreferences,
    audio: QuranAudioController
) {
    val meta by produceState<QuranMeta?>(null) { value = repository.meta() }
    val all = meta ?: return LoadingPane(m, "Открываем аудио…")
    var mode by remember { mutableStateOf(AudioMode.AYAH) }
    var selectedSurah by remember { mutableIntStateOf(1) }
    var selectedAyah by remember { mutableIntStateOf(1) }
    var showDownloadManager by remember { mutableStateOf(false) }
    val track by audio.track.collectAsState()

    val context = LocalContext.current
    val appContext = context.applicationContext
    val downloadRequired by audio.downloadRequired.collectAsState()
    var pendingDownloadReciter by remember { mutableStateOf<String?>(null) }
    var pendingAfterStoragePermission by remember { mutableStateOf<String?>(null) }

    fun enqueueDownload(reciterId: String) {
        AudioDownloadScheduler.enqueueReciter(appContext, reciterId)
        audio.dismissDownloadRequest()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val id = pendingDownloadReciter
        pendingDownloadReciter = null
        if (granted && id != null) enqueueDownload(id)
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val id = pendingAfterStoragePermission
        pendingAfterStoragePermission = null
        if (granted && id != null) enqueueDownload(id)
    }

    fun startReciterDownload(reciterId: String) {
        if (
            Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterStoragePermission = reciterId
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadReciter = reciterId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        enqueueDownload(reciterId)
    }

    downloadRequired?.let { reciterId ->
        val reciterName = RECITERS[reciterId]?.name ?: reciterId
        AlertDialog(
            onDismissRequest = { audio.dismissDownloadRequest() },
            title = { Text("Аудио нужно скачать") },
            text = {
                Text(
                    "Для «$reciterName» полный комплект аятов ещё не установлен. " +
                            "Полный комплект с GitHub будет скачиваться отдельно в фоне; " +
                            "прогресс появится в системном уведомлении. Пока загрузка идёт, " +
                            "можно продолжать читать Коран."
                )
            },
            confirmButton = {
                Button(onClick = { startReciterDownload(reciterId) }) {
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

    LaunchedEffect(track?.surah, track?.ayah) {
        track?.let {
            selectedSurah = it.surah
            if (it.kind == TrackKind.AYAH) selectedAyah = it.ayah.coerceAtLeast(1)
        }
    }

    BackHandler(enabled = showDownloadManager) { showDownloadManager = false }

    if (showDownloadManager) {
        ReciterDownloadsScreen(
            m = m,
            preferences = preferences,
            onBack = { showDownloadManager = false },
            onStartDownload = ::startReciterDownload
        )
        return
    }

    LazyColumn(contentPadding = m.pagePadding, verticalArrangement = Arrangement.spacedBy(m.md)) {
        item {
            PageHeading(
                m,
                "Аудио",
                "Слушайте аяты сразу через интернет или сохраняйте выбранных чтецов полностью для офлайн-прослушивания. Хадр уже находится внутри приложения."
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(m.sm)
            ) {
                FilterChip(selected = mode == AudioMode.AYAH, onClick = { mode = AudioMode.AYAH }, label = { Text("По аятам", fontSize = m.bodySmall) })
                FilterChip(selected = mode == AudioMode.HADR, onClick = { mode = AudioMode.HADR }, label = { Text("Хадр · 7 часов", fontSize = m.bodySmall) })
                FilterChip(selected = false, onClick = { showDownloadManager = true }, label = { Text("Загрузки", fontSize = m.bodySmall) })
                FilterChip(selected = mode == AudioMode.CUSTOM, onClick = { mode = AudioMode.CUSTOM }, label = { Text("Мой чтец", fontSize = m.bodySmall) })
            }
        }
        item {
            when (mode) {
                AudioMode.AYAH -> OnlineAudioPanel(
                    m = m,
                    meta = all,
                    repository = repository,
                    preferences = preferences,
                    audio = audio,
                    selectedSurah = selectedSurah,
                    selectedAyah = selectedAyah,
                    onSelection = { s, a ->
                        selectedSurah = s
                        selectedAyah = a
                    },
                    onDownloadCurrent = { startReciterDownload(preferences.settings.value.reciter) },
                    onManageDownloads = { showDownloadManager = true }
                )
                AudioMode.HADR -> HadrAudioPanel(m, audio)
                AudioMode.CUSTOM -> CustomAudioPanel(m, all, preferences, selectedSurah) { selectedSurah = it }
            }
        }
        if (mode == AudioMode.AYAH || mode == AudioMode.CUSTOM) {
            item { Text("Суры", fontSize = m.heading, fontWeight = FontWeight.SemiBold) }
            items(all.surahs, key = { it.id }) { s ->
                Surface(
                    onClick = { selectedSurah = s.id; selectedAyah = 1 },
                    shape = RoundedCornerShape(m.corner),
                    color = if (selectedSurah == s.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    tonalElevation = m.unit * .12f
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(m.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.md)
                    ) {
                        Text(s.id.toString(), fontSize = m.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(s.nameRu, fontSize = m.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.ayahs} ${pluralizeAyah(s.ayahs)}", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(s.nameAr, fontFamily = QuranFont, fontSize = m.heading, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineAudioPanel(
    m: AdaptiveMetrics,
    meta: QuranMeta,
    repository: QuranRepository,
    preferences: AppPreferences,
    audio: QuranAudioController,
    selectedSurah: Int,
    selectedAyah: Int,
    onSelection: (Int, Int) -> Unit,
    onDownloadCurrent: () -> Unit,
    onManageDownloads: () -> Unit
) {
    val settings by preferences.settings.collectAsState()
    val track by audio.track.collectAsState()
    val playing by audio.playing.collectAsState()
    val position by audio.position.collectAsState()
    val duration by audio.duration.collectAsState()
    val repeat by audio.repeat.collectAsState()
    val error by audio.error.collectAsState()
    val scope = rememberCoroutineScope()
    val sm = meta.surahs.getOrNull(selectedSurah - 1) ?: meta.surahs.first()
    val maxAyah = sm.ayahs.coerceAtLeast(1)
    val ayah = selectedAyah.coerceIn(1, maxAyah)

    Surface(shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .22f) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Text("${sm.nameRu} · аят $ayah", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
            ReciterDropdown(m, preferences, Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(m.sm)
            ) {
                Button(onClick = onDownloadCurrent) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(m.iconSmall))
                    Spacer(Modifier.width(m.xs))
                    Text("Скачать чтеца", fontSize = m.bodySmall)
                }
                OutlinedButton(onClick = onManageDownloads) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(m.iconSmall))
                    Spacer(Modifier.width(m.xs))
                    Text("Скачать других чтецов", fontSize = m.bodySmall)
                }
            }
            Text(
                "При первом интернет-прослушивании аят параллельно сохраняется в офлайн-структуру выбранного чтеца. Поэтому полная загрузка позже не скачивает уже сохранённые аяты повторно.",
                fontSize = m.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectedSurah == 1) {
                OutlinedButton(onClick = { audio.playBismillah(1, sm.nameRu) }) {
                    Text("Басмала · отдельно", fontSize = m.bodySmall)
                }
            }
            error?.let {
                Surface(shape = RoundedCornerShape(m.corner * .55f), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(it, Modifier.fillMaxWidth().padding(m.sm), fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Slider(
                value = position.coerceAtMost(duration.coerceAtLeast(1)).toFloat(),
                onValueChange = { audio.seekTo(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { audio.advance(-1) } }, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.SkipPrevious, "Предыдущий", Modifier.size(m.icon)) }
                IconButton(onClick = { audio.seekTo((position - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.FastRewind, "−10 секунд", Modifier.size(m.iconSmall)) }
                Surface(
                    onClick = {
                        val active = track?.kind == TrackKind.AYAH && track?.surah == selectedSurah && ayah in (track?.ayah ?: -1)..(track?.endAyah ?: -1)
                        if (active) audio.toggle() else audio.playAyah(selectedSurah, ayah, sm.nameRu)
                    },
                    shape = RoundedCornerShape(m.corner),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(m.touch * 1.24f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизвести", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(m.icon))
                    }
                }
                IconButton(onClick = { audio.seekTo((position + 10_000L).coerceAtMost(duration.coerceAtLeast(position + 10_000L))) }, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.FastForward, "+10 секунд", Modifier.size(m.iconSmall)) }
                IconButton(onClick = { scope.launch { audio.advance(1) } }, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.SkipNext, "Следующий", Modifier.size(m.icon)) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(m.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = repeat, onClick = { audio.setRepeat(!repeat) }, leadingIcon = { Icon(Icons.Default.RepeatOne, null, Modifier.size(m.iconSmall)) }, label = { Text("Повтор", fontSize = m.bodySmall) })
                FilterChip(selected = settings.autoAdvance, onClick = { preferences.updateSettings { it.copy(autoAdvance = !it.autoAdvance) } }, label = { Text("Продолжать следующим аятом", fontSize = m.bodySmall) })
            }
            HorizontalDivider()
            AyahSelector(m, ayah, maxAyah) { onSelection(selectedSurah, it) }
        }
    }
}

@Composable
private fun HadrAudioPanel(m: AdaptiveMetrics, audio: QuranAudioController) {
    val playing by audio.hadrPlaying.collectAsState()
    val position by audio.hadrPosition.collectAsState()
    val duration by audio.hadrDuration.collectAsState()
    val total = duration.takeIf { it > 0 } ?: 26_913_250L

    Surface(shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .22f) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Text("Коран за 7 часов", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
            Text("Ахмад Дибан · Хадр", fontSize = m.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Запись уже находится внутри приложения. Автоперелистывание отключено: тайминги страниц не используются. После запуска плеер остаётся глобальным и доступен на любом экране.",
                fontSize = m.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = position.coerceAtMost(total).toFloat(),
                onValueChange = { audio.seekHadr(it.toLong()) },
                valueRange = 0f..total.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.md)) {
                Text(formatTime(position), fontSize = m.bodySmall)
                Surface(
                    onClick = audio::toggleHadr,
                    shape = RoundedCornerShape(m.corner),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(m.touch * 1.24f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Хадр", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(m.icon))
                    }
                }
                Text(formatTime(total), fontSize = m.bodySmall)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = audio::closeHadr) {
                    Icon(Icons.Default.Stop, null, Modifier.size(m.iconSmall))
                    Spacer(Modifier.width(m.xs))
                    Text("Стоп", fontSize = m.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReciterDownloadsScreen(
    m: AdaptiveMetrics,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onStartDownload: (String) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings by preferences.settings.collectAsState()
    val store = remember { AudioOfflineStore(appContext) }
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<Map<String, ReciterOfflineStats>>(emptyMap()) }
    var states by remember { mutableStateOf<Map<String, androidx.work.WorkInfo.State?>>(emptyMap()) }
    var wifiOnly by remember { mutableStateOf(AudioDownloadScheduler.wifiOnly(appContext)) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        stats = store.downloadedStats(RECITERS.keys)
        states = RECITERS.keys.associateWith { AudioDownloadScheduler.state(appContext, it) }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(1_200L)
        }
    }

    deleteCandidate?.let { id ->
        val reciter = RECITERS[id]
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Удалить аудио чтеца?") },
            text = {
                Text(
                    "Будут удалены все сохранённые аяты «${reciter?.name ?: id}», включая незавершённую загрузку. " +
                            "Онлайн-воспроизведение после этого останется доступно."
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleteCandidate = null
                    AudioDownloadScheduler.cancel(appContext, id)
                    scope.launch {
                        store.deleteReciter(id)
                        refresh()
                        message = "Аудио ${reciter?.name ?: id} удалено с устройства."
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") }
            }
        )
    }

    val filtered = remember(query) {
        RECITERS.entries.filter { (_, reciter) ->
            query.isBlank() || reciter.name.contains(query, ignoreCase = true)
        }
    }
    val totalStored = stats.values.sumOf { it.totalBytes }
    val completeCount = stats.values.count { it.complete }
    val activeCount = states.values.count { it == androidx.work.WorkInfo.State.RUNNING || it == androidx.work.WorkInfo.State.ENQUEUED }

    LazyColumn(
        contentPadding = m.pagePadding,
        verticalArrangement = Arrangement.spacedBy(m.md)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.sm)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(m.touch)) {
                    Icon(Icons.Default.ArrowBack, "Назад", modifier = Modifier.size(m.icon))
                }
                Column(Modifier.weight(1f)) {
                    Text("Загрузка чтецов", fontSize = m.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$completeCount полностью · $activeCount загружается · занято ${formatStorageSize(totalStored)}",
                        fontSize = m.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .22f) {
                Column(
                    Modifier.fillMaxWidth().padding(m.lg),
                    verticalArrangement = Arrangement.spacedBy(m.md)
                ) {
                    Text("Управление данными", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Каждый чтец скачивается независимо в фоне. При обрыве сети готовые аяты не теряются, " +
                                "а текущий недокачанный MP3 продолжится с сохранённого байта. Можно остановить загрузку и продолжить позже.",
                        fontSize = m.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.md)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Только Wi‑Fi / безлимитная сеть", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Рекомендуется: полный каталог чтецов может занимать десятки гигабайт.",
                                fontSize = m.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                AudioDownloadScheduler.setWifiOnly(appContext, it)
                                message = "Настройка сети применяется к новым и возобновлённым загрузкам."
                            }
                        )
                    }
                    message?.let {
                        Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(m.iconSmall)) },
                label = { Text("Найти чтеца", fontSize = m.bodySmall) }
            )
        }

        items(filtered, key = { it.key }) { (id, reciter) ->
            val stat = stats[id] ?: ReciterOfflineStats()
            val state = states[id]
            val count = stat.files.coerceAtMost(AudioOfflineStore.EXPECTED_AYAH_FILES)
            val progress = (count.toFloat() / AudioOfflineStore.EXPECTED_AYAH_FILES.toFloat()).coerceIn(0f, 1f)
            val active = state == androidx.work.WorkInfo.State.RUNNING || state == androidx.work.WorkInfo.State.ENQUEUED
            val stateText = when {
                stat.complete -> "Скачан полностью"
                state == androidx.work.WorkInfo.State.RUNNING -> "Скачивается в фоне"
                state == androidx.work.WorkInfo.State.ENQUEUED -> if (wifiOnly) "Ожидает подходящую сеть" else "Ожидает запуска"
                state == androidx.work.WorkInfo.State.BLOCKED -> "Ожидает условий"
                count > 0 || stat.partialBytes > 0L -> "Загрузка приостановлена · можно продолжить"
                else -> "Не скачан"
            }

            Surface(
                shape = RoundedCornerShape(m.corner),
                tonalElevation = m.unit * .16f
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(m.md),
                    verticalArrangement = Arrangement.spacedBy(m.sm)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.sm)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.xs)) {
                                Text(reciter.name, fontSize = m.body, fontWeight = FontWeight.SemiBold)
                                if (id == settings.reciter) {
                                    Text("· выбран", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                "$stateText · ${reciter.bitrateKbps} kbps",
                                fontSize = m.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.CloudDownload,
                            null,
                            tint = if (stat.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(m.iconSmall)
                        )
                    }

                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "$count / ${AudioOfflineStore.EXPECTED_AYAH_FILES} аятов · ${formatStorageSize(stat.totalBytes)} на устройстве" +
                                if (stat.partialBytes > 0L) " · ${formatStorageSize(stat.partialBytes)} ожидает продолжения" else "",
                        fontSize = m.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(m.sm)
                    ) {
                        if (active) {
                            OutlinedButton(onClick = {
                                AudioDownloadScheduler.cancel(appContext, id)
                                message = "Загрузка ${reciter.name} остановлена. Уже сохранённые данные останутся на устройстве."
                            }) {
                                Icon(Icons.Default.Pause, null, Modifier.size(m.iconSmall))
                                Spacer(Modifier.width(m.xs))
                                Text("Приостановить", fontSize = m.bodySmall)
                            }
                        } else if (!stat.complete) {
                            Button(onClick = {
                                onStartDownload(id)
                                message = if (count > 0 || stat.partialBytes > 0L) {
                                    "Продолжаем загрузку ${reciter.name}."
                                } else {
                                    "Загрузка ${reciter.name} запущена в фоне."
                                }
                            }) {
                                Icon(Icons.Default.CloudDownload, null, Modifier.size(m.iconSmall))
                                Spacer(Modifier.width(m.xs))
                                Text(if (count > 0 || stat.partialBytes > 0L) "Продолжить" else "Скачать", fontSize = m.bodySmall)
                            }
                        } else {
                            OutlinedButton(onClick = {}, enabled = false) {
                                Text("Скачано", fontSize = m.bodySmall)
                            }
                        }

                        if (stat.totalBytes > 0L || count > 0) {
                            OutlinedButton(onClick = { deleteCandidate = id }) {
                                Icon(Icons.Default.Delete, null, Modifier.size(m.iconSmall))
                                Spacer(Modifier.width(m.xs))
                                Text("Удалить", fontSize = m.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 МБ"
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) {
        String.format(java.util.Locale.getDefault(), "%.2f ГБ", mib / 1024.0)
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f МБ", mib)
    }
}

@Composable
private fun AyahSelector(m: AdaptiveMetrics, selected: Int, max: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
        (1..max).forEach { a ->
            FilterChip(selected = a == selected, onClick = { onSelect(a) }, label = { Text(a.toString(), fontSize = m.bodySmall) })
        }
    }
}

@Composable
private fun CustomAudioPanel(
    m: AdaptiveMetrics,
    meta: QuranMeta,
    preferences: AppPreferences,
    selectedSurah: Int,
    onSurah: (Int) -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(preferences.customReciter()) }
    var status by remember { mutableStateOf<String?>(null) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var repeat by remember { mutableStateOf(false) }
    val surah = selectedSurah.coerceIn(1, 114)
    val sm = meta.surahs[surah - 1]

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && repeat) { player.seekTo(0); player.play() }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val parsed = linkedMapOf<Int, Uri>()
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }.orEmpty()
            val n = Regex("^(\\d{3})\\.(mp3|m4a)$", RegexOption.IGNORE_CASE).matchEntire(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (n != null && n in 1..114) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                parsed[n] = uri
            }
        }
        if (parsed.size == 114 && (1..114).all { parsed.containsKey(it) }) {
            preferences.saveCustomReciter(parsed); files = parsed; status = "Комплект из 114 сур подключён."
        } else status = "Нужно выбрать ровно 114 файлов: 001.mp3/m4a … 114.mp3/m4a. Распознано: ${parsed.size}."
    }

    fun playCurrent() {
        val uri = files[surah] ?: return
        if (player.currentMediaItem?.localConfiguration?.uri == uri) {
            if (player.isPlaying) player.pause() else player.play()
        } else {
            player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.play()
        }
    }

    Surface(shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .22f) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Text("Мой чтец", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
            Text("Локальные записи не отправляются в интернет. Файлы остаются доступными приложению через Android Storage Access Framework.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                Button(onClick = { launcher.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/*")) }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(m.iconSmall)); Spacer(Modifier.width(m.sm)); Text(if (files.size == 114) "Заменить 114 файлов" else "Выбрать 114 файлов", fontSize = m.bodySmall)
                }
                if (files.isNotEmpty()) OutlinedButton(onClick = { preferences.saveCustomReciter(emptyMap()); files = emptyMap(); player.stop(); status = "Комплект отключён." }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(m.iconSmall)); Spacer(Modifier.width(m.sm)); Text("Отключить", fontSize = m.bodySmall)
                }
            }
            status?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (files.size == 114) {
                HorizontalDivider()
                Text("${sm.id}. ${sm.nameRu}", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                Slider(value = position.coerceAtMost(duration.coerceAtLeast(1)).toFloat(), onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { val n = (surah - 1).coerceAtLeast(1); onSurah(n); files[n]?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare(); player.play() } }, enabled = surah > 1, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(m.icon)) }
                    Surface(onClick = ::playCurrent, shape = RoundedCornerShape(m.corner), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(m.touch * 1.24f)) { Box(contentAlignment = Alignment.Center) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(m.icon)) } }
                    IconButton(onClick = { val n = (surah + 1).coerceAtMost(114); onSurah(n); files[n]?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare(); player.play() } }, enabled = surah < 114, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.SkipNext, null, Modifier.size(m.icon)) }
                    FilterChip(selected = repeat, onClick = { repeat = !repeat }, label = { Text("Повтор", fontSize = m.bodySmall) })
                }
            }
        }
    }
}
