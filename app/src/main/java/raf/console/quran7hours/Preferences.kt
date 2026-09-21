package raf.console.quran7hours

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("q7", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings
    private val _bookmarks = MutableStateFlow(readBookmarks())
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks
    private val _recents = MutableStateFlow(readRecents())
    val recents: StateFlow<List<RecentItem>> = _recents
    private val _readerCoordinate = MutableStateFlow(readReaderCoordinate())
    val readerCoordinate: StateFlow<ReaderCoordinate> = _readerCoordinate

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString("settings", encodeSettings(next).toString()).apply()
    }

    /** Persists the exact place the reader was showing, independently of the current screen. */
    fun saveReaderCoordinate(value: ReaderCoordinate) {
        val safe = value.copy(
            surah = value.surah.coerceIn(1, 114),
            ayah = value.ayah.coerceAtLeast(1),
            page = value.page.coerceIn(1, 604),
            juz = value.juz.coerceIn(1, 30)
        )
        if (_readerCoordinate.value == safe) return
        _readerCoordinate.value = safe
        prefs.edit().putString(
            "readerCoordinate",
            JSONObject()
                .put("surah", safe.surah)
                .put("ayah", safe.ayah)
                .put("page", safe.page)
                .put("juz", safe.juz)
                .put("range", safe.range)
                .toString()
        ).apply()
    }

    fun setBookmark(key: String, color: Long) {
        val next = listOf(BookmarkItem(key, color, System.currentTimeMillis())) + _bookmarks.value.filterNot { it.key == key }
        _bookmarks.value = next
        saveBookmarks(next)
    }

    fun removeBookmark(key: String) {
        val next = _bookmarks.value.filterNot { it.key == key }
        _bookmarks.value = next
        saveBookmarks(next)
    }

    fun addRecent(key: String) {
        val next = (listOf(RecentItem(key, System.currentTimeMillis())) + _recents.value.filterNot { it.key == key }).take(20)
        _recents.value = next
        val arr = JSONArray()
        next.forEach { arr.put(JSONObject().put("key", it.key).put("at", it.at)) }
        prefs.edit().putString("recents", arr.toString()).apply()
    }

    fun saveCustomReciter(uris: Map<Int, Uri>) {
        val obj = JSONObject()
        uris.toSortedMap().forEach { (n, uri) -> obj.put(n.toString(), uri.toString()) }
        prefs.edit().putString("customReciter", obj.toString()).apply()
    }

    fun customReciter(): Map<Int, Uri> {
        val raw = prefs.getString("customReciter", null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEachRemaining { key -> key.toIntOrNull()?.let { put(it, Uri.parse(obj.getString(key))) } }
            }
        }.getOrDefault(emptyMap())
    }

    private fun readSettings(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                themeMode = runCatching { ThemeMode.valueOf(o.optString("themeMode", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM),
                contrast = runCatching { ContrastMode.valueOf(o.optString("contrast", "NORMAL")) }.getOrDefault(ContrastMode.NORMAL),
                themePreset = o.optString("themePreset", "sand"),
                keepAwake = o.optBoolean("keepAwake", false),
                showArabic = o.optBoolean("showArabic", true),
                showTranslation = o.optBoolean("showTranslation", true),
                showTafsir = o.optBoolean("showTafsir", true),
                showFootnotes = o.optBoolean("showFootnotes", true),
                showWordByWord = o.optBoolean("showWordByWord", true),
                tajweed = o.optBoolean("tajweed", true),
                arabicScale = o.optDouble("arabicScale", 1.0).toFloat(),
                translationScale = o.optDouble("translationScale", 1.0).toFloat(),
                wordByWordScale = o.optDouble("wordByWordScale", .40).toFloat(),
                tafsirScale = o.optDouble("tafsirScale", 1.0).toFloat(),
                footnoteScale = o.optDouble("footnoteScale", 1.0).toFloat(),
                lineHeight = o.optDouble("lineHeight", 2.05).toFloat(),
                letterSpacing = o.optDouble("letterSpacing", 0.0).toFloat(),
                mushafWordSpacing = o.optDouble("mushafWordSpacing", .04).toFloat(),
                mushafSpacingMode = runCatching { MushafSpacingMode.valueOf(o.optString("mushafSpacingMode", "ACADEMY")) }.getOrDefault(MushafSpacingMode.ACADEMY),
                mushafHoverMode = runCatching { MushafHoverMode.valueOf(o.optString("mushafHoverMode", "BOTH")) }.getOrDefault(MushafHoverMode.BOTH),
                arabicFont = o.optString("arabicFont", "qpc-v4"),
                reciter = o.optString("reciter", "alafasy").let { if (it == "nabil_rifai" || it !in RECITERS) "alafasy" else it },
                autoAdvance = o.optBoolean("autoAdvance", true),
                autoPageTurnAudio = o.optBoolean("autoPageTurnAudio", false),
                readingMode = runCatching { ReadingMode.valueOf(o.optString("readingMode", "MUSHAF")) }.getOrDefault(ReadingMode.MUSHAF),
                readerShowAudio = o.optBoolean("readerShowAudio", true),
                readerShowArabic = o.optBoolean("readerShowArabic", false),
                readerShowTafsir = o.optBoolean("readerShowTafsir", true),
                contentOrder = o.optJSONArray("contentOrder")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) runCatching { ContentBlock.valueOf(arr.getString(i)) }.getOrNull()?.let(::add) }
                }?.takeIf { it.size == 4 } ?: AppSettings().contentOrder
            )
        }.getOrElse { AppSettings() }
    }

    private fun encodeSettings(s: AppSettings) = JSONObject()
        .put("themeMode", s.themeMode.name).put("contrast", s.contrast.name).put("themePreset", s.themePreset)
        .put("keepAwake", s.keepAwake).put("showArabic", s.showArabic).put("showTranslation", s.showTranslation)
        .put("showTafsir", s.showTafsir).put("showFootnotes", s.showFootnotes).put("showWordByWord", s.showWordByWord)
        .put("tajweed", s.tajweed).put("arabicScale", s.arabicScale).put("translationScale", s.translationScale)
        .put("wordByWordScale", s.wordByWordScale).put("tafsirScale", s.tafsirScale).put("footnoteScale", s.footnoteScale)
        .put("lineHeight", s.lineHeight).put("letterSpacing", s.letterSpacing).put("mushafWordSpacing", s.mushafWordSpacing)
        .put("mushafSpacingMode", s.mushafSpacingMode.name).put("mushafHoverMode", s.mushafHoverMode.name).put("arabicFont", s.arabicFont).put("reciter", s.reciter)
        .put("autoAdvance", s.autoAdvance).put("autoPageTurnAudio", s.autoPageTurnAudio)
        .put("readingMode", s.readingMode.name)
        .put("readerShowAudio", s.readerShowAudio).put("readerShowArabic", s.readerShowArabic).put("readerShowTafsir", s.readerShowTafsir)
        .put("contentOrder", JSONArray(s.contentOrder.map { it.name }))

    private fun readBookmarks(): List<BookmarkItem> {
        val raw = prefs.getString("bookmarks", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) arr.getJSONObject(i).let { add(BookmarkItem(it.getString("key"), it.getLong("color"), it.getLong("createdAt"))) } }
        }.getOrDefault(emptyList())
    }

    private fun saveBookmarks(items: List<BookmarkItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("key", it.key).put("color", it.color).put("createdAt", it.createdAt)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    private fun readRecents(): List<RecentItem> {
        val raw = prefs.getString("recents", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) arr.getJSONObject(i).let { add(RecentItem(it.getString("key"), it.getLong("at"))) } }
        }.getOrDefault(emptyList())
    }
    private fun readReaderCoordinate(): ReaderCoordinate {
        val raw = prefs.getString("readerCoordinate", null) ?: return ReaderCoordinate()
        return runCatching {
            val o = JSONObject(raw)
            ReaderCoordinate(
                surah = o.optInt("surah", 1).coerceIn(1, 114),
                ayah = o.optInt("ayah", 1).coerceAtLeast(1),
                page = o.optInt("page", 1).coerceIn(1, 604),
                juz = o.optInt("juz", 1).coerceIn(1, 30),
                range = o.optString("range", "1–7")
            )
        }.getOrDefault(ReaderCoordinate())
    }

}
