package raf.quran7hours.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.RoundingMode

private val themePresets = listOf(
    "sand" to "Песок", "brown" to "Тёплый коричневый", "olive" to "Олива", "emerald" to "Изумруд",
    "teal" to "Бирюза", "blue" to "Ночной синий", "violet" to "Фиолетовый", "rose" to "Роза",
    "graphite" to "Графит", "paper" to "Бумага", "sepia" to "Сепия", "mint" to "Мята"
)
private val fontOptions = listOf(
    "qpc-v4" to "QPC V4 Tajweed", "qpc-hafs" to "KFGQPC Uthman Taha Naskh", "me-quran" to "Me Quran",
    "digital-v1" to "Digital Khatt V1", "digital-v2" to "Digital Khatt V2", "scheherazade" to "Scheherazade New",
    "amiri" to "Amiri Quran", "noto" to "Noto Naskh Arabic", "indopak" to "Indopak Nastaleeq",
    "noorehira" to "Noorehira", "pdms" to "PDMS Saleem", "hamdullah" to "Shaikh Hamdullah", "traditional" to "Traditional Arabic"
)
private val blockLabels = mapOf(
    ContentBlock.AZAN_TRANSLATION to "Перевод · Azan.ru",
    ContentBlock.ALAUTDINOV_TRANSLATION to "Перевод · Аляутдинов",
    ContentBlock.AZAN_TAFSIR to "Тафсир · Azan.ru",
    ContentBlock.ALAUTDINOV_TAFSIR to "Тафсир · Аляутдинов"
)

@Composable
fun SettingsScreen(m: AdaptiveMetrics, repository: QuranRepository, preferences: AppPreferences) {
    val s by preferences.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alautCount by remember { mutableIntStateOf(0) }
    var alautMessage by remember { mutableStateOf<String?>(null) }
    var wordCount by remember { mutableIntStateOf(0) }
    var wordMessage by remember { mutableStateOf<String?>(null) }

    produceState(Unit) {
        alautCount = repository.alautdinov().size
        wordCount = repository.importedWordMeaningCount()
        value = Unit
    }

    fun readText(uri: Uri, onDone: (String) -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Не удалось прочитать файл") } }
            .onSuccess(onDone).onFailure { alautMessage = it.message ?: "Ошибка чтения" }
    }

    val alautLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readText(uri) { text -> scope.launch { runCatching { repository.importAlautdinov(text) }.onSuccess { alautCount = it; alautMessage = "Импортировано: $it ${pluralizeAyah(it)}." }.onFailure { alautMessage = it.message } } }
    }
    val wordLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Не удалось прочитать файл") }
                repository.importWordMeanings(text)
            }.onSuccess { wordCount = it; wordMessage = "Импортировано $it слов во вспомогательный словарь." }.onFailure { wordMessage = it.message }
        }
    }

    LazyColumn(contentPadding = m.pagePadding, verticalArrangement = Arrangement.spacedBy(m.md)) {
        item { PageHeading(m, "Настройки", "Параметры сохраняются локально на устройстве и применяются мгновенно.") }
        item {
            SettingsSection(m, "Режим чтения и нижняя панель") {
                Text("Режим чтения", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                ChoiceRow(
                    m,
                    listOf(
                        ReadingMode.SURAH to "Сура",
                        ReadingMode.PAGE to "Страница",
                        ReadingMode.MUSHAF to "Мусхаф"
                    ),
                    s.readingMode
                ) { mode -> preferences.updateSettings { it.copy(readingMode = mode) } }
                Text(
                    "Выбранный режим запоминается. Переходы из меню, закладок и быстрого перехода открываются в нём же.",
                    fontSize = m.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
                Text("Содержимое панели чтения", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                SettingSwitch(m, "Аудио", "Показывать пункт аудио в нижней панели чтения.", s.readerShowAudio) {
                    preferences.updateSettings { x -> x.copy(readerShowAudio = it) }
                }
                SettingSwitch(m, "Арабский текст", "В режиме Мусхаф по умолчанию скрыт в панели перевода и тафсира.", s.readerShowArabic) {
                    preferences.updateSettings { x -> x.copy(readerShowArabic = it) }
                }
                SettingSwitch(m, "Тафсир", "Перевод показывается всегда; этот переключатель управляет только тафсиром.", s.readerShowTafsir) {
                    preferences.updateSettings { x -> x.copy(readerShowTafsir = it) }
                }
                Text("Перевод включён всегда и не отключается.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            SettingsSection(m, "Тема и экран") {
                ChoiceRow(m, listOf(ThemeMode.SYSTEM to "Системная", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная"), s.themeMode) { preferences.updateSettings { x -> x.copy(themeMode = it) } }
                SettingSwitch(m, "Не выключать экран", "Экран остаётся активным во время чтения.", s.keepAwake) { preferences.updateSettings { x -> x.copy(keepAwake = it) } }
                Text("Контраст", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                ChoiceRow(m, listOf(ContrastMode.NORMAL to "Обычный", ContrastMode.MEDIUM to "Средний", ContrastMode.HIGH to "Высокий"), s.contrast) { preferences.updateSettings { x -> x.copy(contrast = it) } }
                Text("Цветовая тема", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                    themePresets.forEach { (id, label) -> FilterChip(selected = s.themePreset == id, onClick = { preferences.updateSettings { it.copy(themePreset = id) } }, label = { Text(label, fontSize = m.bodySmall) }) }
                }
            }
        }
        item {
            SettingsSection(m, "Отображение") {
                SettingSwitch(m, "Арабский текст", null, s.showArabic) { preferences.updateSettings { x -> x.copy(showArabic = it) } }
                SettingSwitch(m, "Перевод", null, s.showTranslation) { preferences.updateSettings { x -> x.copy(showTranslation = it) } }
                SettingSwitch(m, "Тафсир", null, s.showTafsir) { preferences.updateSettings { x -> x.copy(showTafsir = it) } }
                SettingSwitch(m, "Сноски", null, s.showFootnotes) { preferences.updateSettings { x -> x.copy(showFootnotes = it) } }
                SettingSwitch(m, "Пословный перевод", null, s.showWordByWord) { preferences.updateSettings { x -> x.copy(showWordByWord = it) } }
                SettingSwitch(m, "Таджвид", null, s.tajweed) { preferences.updateSettings { x -> x.copy(tajweed = it) } }
                RangeSetting(m, "Арабский текст", s.arabicScale, .63f, 1.68f, "×") { preferences.updateSettings { x -> x.copy(arabicScale = it) } }
                RangeSetting(m, "Пословный перевод", s.wordByWordScale, .26f, .56f, "× от арабского") { preferences.updateSettings { x -> x.copy(wordByWordScale = it) } }
                RangeSetting(m, "Перевод", s.translationScale, .76f, 1.76f, "×") { preferences.updateSettings { x -> x.copy(translationScale = it) } }
                RangeSetting(m, "Тафсир", s.tafsirScale, .76f, 1.65f, "×") { preferences.updateSettings { x -> x.copy(tafsirScale = it) } }
                RangeSetting(m, "Сноски", s.footnoteScale, .70f, 1.55f, "×") { preferences.updateSettings { x -> x.copy(footnoteScale = it) } }
                RangeSetting(m, "Высота строки арабского", s.lineHeight, 1.35f, 2.80f, "×") { preferences.updateSettings { x -> x.copy(lineHeight = it) } }
                RangeSetting(m, "Расстояние между буквами", s.letterSpacing, -1f, 5f, "") { preferences.updateSettings { x -> x.copy(letterSpacing = it) } }
            }
        }
        item {
            SettingsSection(m, "Мусхаф · строки и подсказки") {
                Text("Канонические строки всех 604 страниц уже встроены. Ни одно слово не переносится на другую строку; меняется только горизонтальная геометрия.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(m, listOf(MushafSpacingMode.FIXED to "Одинаковый интервал", MushafSpacingMode.ACADEMY to "По печатному Мусхафу"), s.mushafSpacingMode) { preferences.updateSettings { x -> x.copy(mushafSpacingMode = it) } }
                if (s.mushafSpacingMode == MushafSpacingMode.FIXED) RangeSetting(m, "Расстояние между словами", s.mushafWordSpacing, 0f, .32f, "em") { preferences.updateSettings { x -> x.copy(mushafWordSpacing = it) } }
                Text("Подсказка по касанию", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                ChoiceRow(m, listOf(MushafHoverMode.BOTH to "Слова + аят", MushafHoverMode.WORD to "Только слова", MushafHoverMode.AYAH to "Весь аят", MushafHoverMode.OFF to "Выкл"), s.mushafHoverMode) { preferences.updateSettings { x -> x.copy(mushafHoverMode = it) } }
                Surface(shape = RoundedCornerShape(m.corner * .65f), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha=.45f)) {
                    Column(Modifier.fillMaxWidth().padding(m.md), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                        Text("604 страничных JSON подключены", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                        Text("Пословный корпус и геометрия страниц находятся в assets приложения и работают без сайта и браузерного кеша.", fontSize = m.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(m.sm), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { wordLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }) { Icon(Icons.Default.UploadFile, null, Modifier.size(m.iconSmall)); Text("Импорт словаря", fontSize = m.bodySmall, modifier = Modifier.padding(start=m.sm)) }
                    if (wordCount > 0) IconButton(onClick = { scope.launch { repository.clearWordMeanings(); wordCount = 0; wordMessage = "Вспомогательный словарь удалён." } }, modifier = Modifier.size(m.touch)) { Icon(Icons.Default.Delete, "Удалить", Modifier.size(m.iconSmall)) }
                }
                wordMessage?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text("604 страницы, пословные данные, геометрия и Хадр уже входят в приложение. Выбранные QUL-шрифты при первом использовании загружаются по HTTPS и затем хранятся в приватном кеше приложения.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSection(m, "Арабский шрифт") {
                Text("Гарнитуры QUL (KFGQPC, Me Quran, Digital Khatt и Indopak) автоматически загружаются при первом выборе и кешируются. Для системных гарнитур используется установленная версия Android; при её отсутствии применяется совместимый арабский fallback.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                    fontOptions.forEach { (id, label) -> FilterChip(selected = s.arabicFont == id, onClick = { preferences.updateSettings { it.copy(arabicFont = id) } }, label = { Text(label, fontSize = m.bodySmall) }) }
                }
            }
        }
        item {
            SettingsSection(m, "Чтец") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                    RECITERS.forEach { (id, r) -> FilterChip(selected = s.reciter == id, onClick = { preferences.updateSettings { it.copy(reciter = id) } }, label = { Text(r.name, fontSize = m.bodySmall) }) }
                }
                SettingSwitch(m, "Автоматически продолжать следующим аятом", null, s.autoAdvance) { preferences.updateSettings { x -> x.copy(autoAdvance = it) } }
                SettingSwitch(m, "Автоперелистывание страниц", "Временно отключено до окончательной сверки таймингов.", false, enabled = false) { }
                Text("Хадр · Ахмад Дибан · непрерывный локальный файл 7:28:33", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSection(m, "Перевод и тафсир Шамиля Аляутдинова") {
                Text(if (alautCount > 0) "Подключено: $alautCount ${pluralizeAyah(alautCount)}" else "Корпус не подключён", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                Text("Полный защищённый авторским правом текст не включён. Лицензированный JSON можно импортировать локально.", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Форматы: {translation:{\"1:1\":\"…\"}, tafsir:{…}} или {\"1:1\":{translation:\"…\",tafsir:\"…\"}}.", fontSize = m.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(m.sm), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { alautLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }) { Icon(Icons.Default.UploadFile, null, Modifier.size(m.iconSmall)); Text("Импорт JSON", fontSize = m.bodySmall, modifier=Modifier.padding(start=m.sm)) }
                    if (alautCount > 0) OutlinedButton(onClick = { scope.launch { repository.clearAlautdinov(); alautCount = 0; alautMessage = "Локальный корпус удалён." } }) { Icon(Icons.Default.Delete, null, Modifier.size(m.iconSmall)); Text("Удалить", fontSize = m.bodySmall, modifier=Modifier.padding(start=m.sm)) }
                }
                alautMessage?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            SettingsSection(m, "Порядок переводов и тафсиров") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
                    OutlinedButton(onClick = { preferences.updateSettings { it.copy(contentOrder = listOf(ContentBlock.AZAN_TRANSLATION, ContentBlock.ALAUTDINOV_TRANSLATION, ContentBlock.AZAN_TAFSIR, ContentBlock.ALAUTDINOV_TAFSIR)) } }) { Text("Сначала переводы", fontSize = m.bodySmall) }
                    OutlinedButton(onClick = { preferences.updateSettings { it.copy(contentOrder = listOf(ContentBlock.AZAN_TRANSLATION, ContentBlock.AZAN_TAFSIR, ContentBlock.ALAUTDINOV_TRANSLATION, ContentBlock.ALAUTDINOV_TAFSIR)) } }) { Text("По авторам", fontSize = m.bodySmall) }
                }
                s.contentOrder.forEachIndexed { i, block ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${blockLabels[block]}", modifier = Modifier.weight(1f), fontSize = m.body)
                        IconButton(onClick = { moveContent(preferences, i, -1) }, enabled = i > 0, modifier=Modifier.size(m.touch*.78f)) { Icon(Icons.Default.ArrowUpward, null, Modifier.size(m.iconSmall)) }
                        IconButton(onClick = { moveContent(preferences, i, 1) }, enabled = i < s.contentOrder.lastIndex, modifier=Modifier.size(m.touch*.78f)) { Icon(Icons.Default.ArrowDownward, null, Modifier.size(m.iconSmall)) }
                    }
                }
            }
        }
    }
}

private fun moveContent(p: AppPreferences, index: Int, direction: Int) {
    p.updateSettings { s ->
        val list = s.contentOrder.toMutableList(); val target = index + direction
        if (target !in list.indices) s else { val x = list[index]; list[index] = list[target]; list[target] = x; s.copy(contentOrder = list) }
    }
}

@Composable
private fun SettingsSection(m: AdaptiveMetrics, title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .15f) {
        Column(Modifier.fillMaxWidth().padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.md)) {
            Text(title, fontSize = m.heading, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha=.18f))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(m: AdaptiveMetrics, label: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.md)) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = m.body, fontWeight = FontWeight.SemiBold); hint?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(m: AdaptiveMetrics, values: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(m.sm)) {
        values.forEach { (v, label) -> FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label, fontSize = m.bodySmall) }) }
    }
}

@Composable
private fun RangeSetting(m: AdaptiveMetrics, label: String, value: Float, min: Float, max: Float, unit: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.xs)) {
        val shown = value.toBigDecimal().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = m.body); Text("$shown$unit", fontSize = m.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}
