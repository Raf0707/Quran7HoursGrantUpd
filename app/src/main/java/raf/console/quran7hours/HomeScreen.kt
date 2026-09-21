package raf.console.quran7hours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun HomeScreen(
    m: AdaptiveMetrics,
    repository: QuranRepository,
    preferences: AppPreferences,
    navigator: AppNavigator
) {
    val meta by produceState<QuranMeta?>(null) { value = repository.meta() }
    val recents by preferences.recents.collectAsState()
    val settings by preferences.settings.collectAsState()
    val recentPages by produceState<Map<String, Int>>(emptyMap(), recents) {
        val out = linkedMapOf<String, Int>()
        recents.take(20).forEach { recent ->
            out[recent.key] = runCatching { repository.resolve(recent.key)?.second?.p }.getOrNull() ?: 1
        }
        value = out
    }
    var query by remember { mutableStateOf("") }
    val filtered = meta?.surahs?.filter {
        query.isBlank() || "${it.id} ${it.nameRu} ${it.meaning} ${it.nameAr}".contains(query, ignoreCase = true)
    }.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = m.pagePadding,
        verticalArrangement = Arrangement.spacedBy(m.lg)
    ) {
        item {
            if (m.isWide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.lg), verticalAlignment = Alignment.CenterVertically) {
                    HeroText(m, navigator, settings.readingMode, Modifier.weight(1.6f))
                    HeroStats(m, Modifier.weight(.7f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(m.lg)) {
                    HeroText(m, navigator, settings.readingMode, Modifier.fillMaxWidth())
                    HeroStats(m, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(m.md)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ПРОДОЛЖИТЬ", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("Недавнее чтение", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Default.Timer, null, modifier = Modifier.size(m.icon), tint = MaterialTheme.colorScheme.primary)
                }
                if (recents.isEmpty()) {
                    Surface(shape = RoundedCornerShape(m.corner), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                        Text("Здесь появятся аяты, к которым вы возвращались.", fontSize = m.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(m.lg))
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(m.md)) {
                        items(recents.take(8), key = { it.key }) { recent ->
                            val s = recent.key.substringBefore(':').toIntOrNull() ?: 1
                            val a = recent.key.substringAfter(':').toIntOrNull() ?: 1
                            val sm = meta?.surahs?.getOrNull(s - 1)
                            Surface(onClick = { navigator.go(readingRoute(settings.readingMode, s, a, recentPages[recent.key] ?: sm?.pageStart ?: 1)) }, shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .2f) {
                                Column(Modifier.padding(m.lg), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                                    Text(sm?.nameRu ?: "Сура $s", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Аят $a", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(m.md)) {
                Text("СОДЕРЖАНИЕ", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("114 сур Корана", fontSize = m.heading, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(m.iconSmall)) },
                    label = { Text("Найти суру", fontSize = m.bodySmall) }
                )
            }
        }
        items(filtered, key = { it.id }) { s ->
            Surface(onClick = { navigator.go(readingRoute(settings.readingMode, s.id, 1, s.pageStart)) }, shape = RoundedCornerShape(m.corner * .75f), tonalElevation = m.unit * .15f) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = m.md, vertical = m.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.md)
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(m.touch * .78f)) {
                        Box(contentAlignment = Alignment.Center) { Text(s.id.toString(), fontSize = m.body, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.xs)) {
                        Text(s.nameRu, fontSize = m.body, fontWeight = FontWeight.SemiBold)
                        Text("${s.meaning} · ${if (s.revelation == "meccan") "Мекка" else "Медина"}", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(s.nameAr, fontFamily = QuranFont, fontSize = m.body * 1.28f, modifier = Modifier.weight(.7f), textAlign = TextAlign.End)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s.ayahs.toString(), fontSize = m.body, fontWeight = FontWeight.Bold)
                        Text(pluralizeAyah(s.ayahs), fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(m.iconSmall), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun HeroText(m: AdaptiveMetrics, navigator: AppNavigator, readingMode: ReadingMode, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.md)) {
        Surface(shape = RoundedCornerShape(m.corner), color = MaterialTheme.colorScheme.primaryContainer) {
            Text("Чтение · Таджвид · Тафсир · Аудио", fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = m.md, vertical = m.sm))
        }
        Text("Священный Коран.\nСпокойно, точно, без перегруза.", fontSize = m.title, lineHeight = m.title * 1.08f, fontWeight = FontWeight.SemiBold)
        Text("Три режима чтения, 604 страницы классического мусхафа, быстрый переход к любому аяту и полный контроль отображения.", fontSize = m.body, lineHeight = m.body * 1.5f, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.md), verticalArrangement = Arrangement.spacedBy(m.sm)) {
            Button(onClick = { navigator.go(readingRoute(readingMode, 1, 1, 1)) }) {
                Icon(Icons.Default.Book, null, modifier = Modifier.size(m.iconSmall)); Text("Начать чтение", fontSize = m.bodySmall, modifier = Modifier.padding(start = m.sm))
            }
            Button(onClick = { navigator.go(AppRoute.Settings) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text("Настройки чтения", fontSize = m.bodySmall); Icon(Icons.Default.ArrowForward, null, modifier = Modifier.padding(start = m.sm).size(m.iconSmall))
            }
        }
    }
}

@Composable
private fun HeroStats(m: AdaptiveMetrics, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(m.corner), color = MaterialTheme.colorScheme.primary) {
        Column(Modifier.fillMaxWidth().padding(m.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.lg)) {
            Icon(Icons.Default.Map, null, modifier = Modifier.size(m.xl), tint = MaterialTheme.colorScheme.onPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(m, "604", "страницы")
                Stat(m, "114", "сур")
                Stat(m, "6236", "аятов")
            }
        }
    }
}

@Composable
private fun Stat(m: AdaptiveMetrics, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = m.heading, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        Text(label, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f))
    }
}
