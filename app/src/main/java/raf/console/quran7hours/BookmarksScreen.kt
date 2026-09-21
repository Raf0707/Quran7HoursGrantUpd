package raf.console.quran7hours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private data class BookmarkRow(val mark: BookmarkItem, val surahName: String, val ayah: Ayah?)

@Composable
fun BookmarksScreen(m: AdaptiveMetrics, repository: QuranRepository, preferences: AppPreferences, navigator: AppNavigator) {
    val marks by preferences.bookmarks.collectAsState()
    val settings by preferences.settings.collectAsState()
    val rows by produceState<List<BookmarkRow>>(emptyList(), marks) {
        value = marks.mapNotNull { mark ->
            val s = mark.key.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val a = mark.key.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
            val sd = runCatching { repository.surah(s) }.getOrNull() ?: return@mapNotNull null
            BookmarkRow(mark, sd.nameRu, sd.ayahs.firstOrNull { it.a == a })
        }
    }
    LazyColumn(contentPadding = m.pagePadding, verticalArrangement = Arrangement.spacedBy(m.md)) {
        item {
            TextButton(onClick = { navigator.go(AppRoute.Home) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(m.iconSmall))
                Text("На главную", fontSize = m.bodySmall)
            }
        }
        item { PageHeading(m, "Закладки", if (marks.isEmpty()) "Сохранённых аятов пока нет." else "Сохранено: ${marks.size}") }
        if (rows.isEmpty()) item { EmptyPane(m, "Откройте меню нужного аята и выберите цвет закладки.") }
        items(rows, key = { it.mark.key }) { row ->
            val s = row.mark.key.substringBefore(':').toInt(); val a = row.mark.key.substringAfter(':').toInt()
            Surface(onClick = { navigator.go(readingRoute(settings.readingMode, s, a, row.ayah?.p ?: 1)) }, shape = RoundedCornerShape(m.corner), tonalElevation = m.unit * .12f) {
                Row(Modifier.fillMaxWidth().padding(m.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.md)) {
                    Surface(shape = CircleShape, color = Color(row.mark.color), modifier = Modifier.size(m.md)) {}
                    Column(Modifier.weight(1f)) {
                        Text("${row.surahName} · $a", fontSize = m.body, fontWeight = FontWeight.SemiBold)
                        row.ayah?.tr?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = m.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    }
                    IconButton(onClick = { preferences.removeBookmark(row.mark.key) }, modifier = Modifier.size(m.touch * .76f)) { Icon(Icons.Default.BookmarkRemove, "Удалить", Modifier.size(m.iconSmall)) }
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(m.iconSmall), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
