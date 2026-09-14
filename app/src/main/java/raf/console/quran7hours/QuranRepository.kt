package raf.quran7hours.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class QuranRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var metaCache: QuranMeta? = null
    private var pageIndexCache: Map<String, List<String>>? = null
    private val metaMutex = Mutex()
    private val pageIndexMutex = Mutex()
    private val geometryMutex = Mutex()
    @Volatile private var geometryReady = false
    private val geometryPages = arrayOfNulls<List<MushafLineGeometry>>(605)
    private val surahCache = ConcurrentHashMap<Int, SurahData>()
    private val wordPageLocks = ConcurrentHashMap<Int, Mutex>()
    private val wordPageCache = object : LinkedHashMap<Int, WordPage>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WordPage>?): Boolean = size > 10
    }
    private var alautdinovCache: Map<String, AlautdinovEntry>? = null
    private var wordMeaningCache: Map<String, String>? = null

    // Quran text is fully bundled. Warm it once in bounded background workers so
    // opening Surah/Page never has to perform the first JSON parse on the UI path.
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val quranTextPreloadStarted = AtomicBoolean(false)

    private suspend fun assetText(path: String): String = withContext(Dispatchers.IO) {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private suspend inline fun <reified T> decodeAsset(path: String): T = withContext(Dispatchers.IO) {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<T>(reader.readText())
        }
    }

    suspend fun meta(): QuranMeta {
        metaCache?.let { return it }
        return metaMutex.withLock {
            metaCache ?: decodeAsset<QuranMeta>("quran/data/quran-meta.json").also { metaCache = it }
        }
    }

    suspend fun surah(id: Int): SurahData {
        val safe = id.coerceIn(1, 114)
        surahCache[safe]?.let { return it }
        val parsed = decodeAsset<SurahData>("quran/data/surahs/${safe.toString().padStart(3, '0')}.json")
        return surahCache.putIfAbsent(safe, parsed) ?: parsed
    }

    private suspend fun pageIndex(): Map<String, List<String>> {
        pageIndexCache?.let { return it }
        return pageIndexMutex.withLock {
            pageIndexCache ?: decodeAsset<Map<String, List<String>>>("quran/data/page-index.json")
                .also { pageIndexCache = it }
        }
    }

    suspend fun page(page: Int): List<PageAyah> {
        val p = page.coerceIn(1, 604)
        val keys = pageIndex()[p.toString()].orEmpty()
        val meta = meta()
        val data = keys.map { it.substringBefore(':').toInt() }.distinct().associateWith { surah(it) }
        return keys.mapNotNull { key ->
            val s = key.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val a = key.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
            val sd = data[s] ?: return@mapNotNull null
            val ay = sd.ayahs.firstOrNull { it.a == a } ?: return@mapNotNull null
            val sm = meta.surahs.getOrNull(s - 1) ?: return@mapNotNull null
            PageAyah(s, sm.nameRu, sm.nameAr, ay)
        }
    }

    suspend fun resolve(key: String): Pair<SurahData, Ayah>? {
        val s = key.substringBefore(':').toIntOrNull() ?: return null
        val a = key.substringAfter(':').toIntOrNull() ?: return null
        if (s !in 1..114) return null
        val sd = surah(s)
        return sd.ayahs.firstOrNull { it.a == a }?.let { sd to it }
    }

    suspend fun wordPage(page: Int): WordPage {
        val p = page.coerceIn(1, 604)
        synchronized(wordPageCache) { wordPageCache[p]?.let { return it } }

        // QPC rendering and fallback/tooltip UI can request the same page at the
        // same time. Parse it once instead of doing duplicate JSON work.
        val lock = wordPageLocks.computeIfAbsent(p) { Mutex() }
        return lock.withLock {
            synchronized(wordPageCache) { wordPageCache[p]?.let { return@withLock it } }
            var parsed = decodeAsset<WordPage>("quran/data/word-by-word/pages/${p.toString().padStart(3, '0')}.json")
            val overrides = wordMeanings()
            if (overrides.isNotEmpty()) {
                parsed = parsed.copy(words = parsed.words.map { w -> overrides[w.location]?.let { w.copy(translation = it) } ?: w })
            }
            synchronized(wordPageCache) { wordPageCache[p] = parsed }
            parsed
        }
    }

    private suspend fun ensureGeometryLoaded() {
        if (geometryReady) return
        geometryMutex.withLock {
            if (geometryReady) return
            val text = assetText("quran/data/mushaf-reference-geometry.json")
            val parsed = withContext(Dispatchers.Default) {
                val root = JSONObject(text).getJSONObject("pages")
                arrayOfNulls<List<MushafLineGeometry>>(605).also { target ->
                    for (page in 1..604) {
                        val arr = root.optJSONArray(page.toString()) ?: continue
                        target[page] = buildList {
                            for (i in 0 until arr.length()) {
                                val row = arr.optJSONArray(i) ?: continue
                                add(
                                    MushafLineGeometry(
                                        (row.optInt(0, 1000) / 1000f).coerceIn(.48f, 1f),
                                        row.optString(1, "c").firstOrNull() ?: 'c'
                                    )
                                )
                            }
                        }
                    }
                }
            }
            for (page in 1..604) geometryPages[page] = parsed[page]
            geometryReady = true
        }
    }

    fun cachedGeometry(page: Int): List<MushafLineGeometry> =
        if (geometryReady) geometryPages[page.coerceIn(1, 604)].orEmpty() else emptyList()

    suspend fun geometry(page: Int): List<MushafLineGeometry> {
        ensureGeometryLoaded()
        return geometryPages[page.coerceIn(1, 604)].orEmpty()
    }

    suspend fun alautdinov(): Map<String, AlautdinovEntry> {
        alautdinovCache?.let { return it }
        val imported = File(context.filesDir, "alautdinov.json")
        val text = withContext(Dispatchers.IO) {
            if (imported.exists()) imported.readText(Charsets.UTF_8) else assetText("quran/data/providers/alautdinov.json")
        }
        return withContext(Dispatchers.Default) { parseAlautdinov(text) }.also { alautdinovCache = it }
    }

    suspend fun importAlautdinov(text: String): Int = withContext(Dispatchers.IO) {
        val parsed = parseAlautdinov(text)
        require(parsed.isNotEmpty()) { "В JSON не найдено записей формата 1:1" }
        File(context.filesDir, "alautdinov.json").writeText(text, Charsets.UTF_8)
        alautdinovCache = parsed
        parsed.size
    }

    suspend fun clearAlautdinov() = withContext(Dispatchers.IO) {
        File(context.filesDir, "alautdinov.json").delete()
        alautdinovCache = null
    }

    private fun parseAlautdinov(text: String): Map<String, AlautdinovEntry> {
        return runCatching {
            val root = JSONObject(text)
            val out = linkedMapOf<String, AlautdinovEntry>()
            val tr = root.optJSONObject("translation")
            val tf = root.optJSONObject("tafsir")
            if (tr != null || tf != null) {
                val keys = linkedSetOf<String>()
                tr?.keys()?.forEachRemaining { key -> keys.add(key) }
                tf?.keys()?.forEachRemaining { key -> keys.add(key) }
                keys.filter { it.matches(Regex("\\d{1,3}:\\d{1,3}")) }.forEach { key ->
                    val a = tr?.optString(key).orEmpty()
                    val b = tf?.optString(key).orEmpty()
                    if (a.isNotBlank() || b.isNotBlank()) out[key] = AlautdinovEntry(a, b)
                }
            } else {
                root.keys().forEachRemaining { key ->
                    if (!key.matches(Regex("\\d{1,3}:\\d{1,3}"))) return@forEachRemaining
                    root.optJSONObject(key)?.let { v ->
                        val a = v.optString("translation")
                        val b = v.optString("tafsir")
                        if (a.isNotBlank() || b.isNotBlank()) out[key] = AlautdinovEntry(a, b)
                    }
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private suspend fun wordMeanings(): Map<String, String> {
        wordMeaningCache?.let { return it }
        val file = File(context.filesDir, "word-meanings.json")
        val map = withContext(Dispatchers.IO) {
            if (!file.exists()) emptyMap() else runCatching {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                buildMap { obj.keys().forEachRemaining { key -> obj.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } } }
            }.getOrDefault(emptyMap())
        }
        wordMeaningCache = map
        return map
    }

    suspend fun importedWordMeaningCount(): Int = wordMeanings().size

    suspend fun importWordMeanings(text: String): Int = withContext(Dispatchers.IO) {
        val map = normalizeWordMeanings(text)
        require(map.isNotEmpty()) { "В JSON не найден пословный перевод. Поддерживаются Q7 map, страничный Quran Academy JSON и ответы Digital Quran /words." }
        val out = JSONObject(); map.forEach { (k, v) -> out.put(k, v) }
        File(context.filesDir, "word-meanings.json").writeText(out.toString(), Charsets.UTF_8)
        wordMeaningCache = map
        synchronized(wordPageCache) { wordPageCache.clear() }
        map.size
    }

    suspend fun clearWordMeanings() = withContext(Dispatchers.IO) {
        File(context.filesDir, "word-meanings.json").delete()
        wordMeaningCache = emptyMap()
        synchronized(wordPageCache) { wordPageCache.clear() }
    }

    private fun normalizeWordMeanings(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun translation(v: Any?): String = when (v) {
            is String -> v.trim()
            is JSONObject -> listOf("translation", "meaning", "text", "gloss").firstNotNullOfOrNull { k -> v.optString(k).trim().takeIf(String::isNotBlank) }.orEmpty()
            else -> ""
        }
        fun put(s: Int, a: Int, pos: Int, v: Any?) {
            if (s !in 1..114 || a < 1 || pos < 1) return
            translation(v).takeIf(String::isNotBlank)?.let { out["$s:$a:$pos"] = it }
        }
        fun rows(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val loc = row.optString("location").split(':').mapNotNull(String::toIntOrNull)
                val s = row.optInt("surah", row.optInt("surah_number", loc.getOrNull(0) ?: 0))
                val a = row.optInt("ayah", row.optInt("ayah_number", row.optInt("verse", loc.getOrNull(1) ?: 0)))
                val p = row.optInt("position", row.optInt("word", row.optInt("word_number", loc.getOrNull(2) ?: 0)))
                put(s, a, p, row)
            }
        }
        runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) { rows(JSONArray(trimmed)); return@runCatching }
            val root = JSONObject(trimmed)
            root.optJSONArray("words")?.let { rows(it); if (out.isNotEmpty()) return@runCatching }
            root.optJSONArray("data")?.let { data ->
                for (i in 0 until data.length()) {
                    val ay = data.optJSONObject(i) ?: continue
                    val s = ay.optInt("surah_number", ay.optInt("surah", 0)); val a = ay.optInt("number", ay.optInt("ayah", ay.optInt("ayah_number", 0)))
                    val words = ay.optJSONArray("words") ?: continue
                    for (j in 0 until words.length()) { val w = words.optJSONObject(j) ?: continue; put(s, a, w.optInt("position", w.optInt("number", w.optInt("word_number", 0))), w) }
                }
                if (out.isNotEmpty()) return@runCatching
            }
            root.optJSONObject("content")?.optJSONArray("surahs")?.let { surahs ->
                for (i in 0 until surahs.length()) {
                    val so = surahs.optJSONObject(i) ?: continue; val sn = so.optInt("number", so.optInt("surah", so.optInt("id", 0))); val ayahs = so.optJSONArray("ayahs") ?: continue
                    for (j in 0 until ayahs.length()) {
                        val ay = ayahs.optJSONObject(j) ?: continue; val an = ay.optInt("number", ay.optInt("ayah", ay.optInt("id", 0))); val words = ay.optJSONArray("words") ?: continue
                        for (k in 0 until words.length()) { val w = words.optJSONObject(k) ?: continue; put(sn, an, w.optInt("number", w.optInt("position", w.optInt("word", 0))), w) }
                    }
                }
                if (out.isNotEmpty()) return@runCatching
            }
            val dict = root.optJSONObject("words") ?: root
            dict.keys().forEachRemaining { key ->
                if (!key.matches(Regex("\\d{1,3}:\\d{1,3}:\\d{1,3}"))) return@forEachRemaining
                val parts = key.split(':').map(String::toInt); put(parts[0], parts[1], parts[2], dict.opt(key))
            }
        }
        return out
    }

    /**
     * Starts expensive shared Mushaf parsing while the home screen is visible.
     * Nothing here blocks application startup and everything is read from APK
     * assets. The large geometry JSON is converted once into compact per-page
     * lists, then its JSONObject tree is released.
     */
    suspend fun prewarmMushafStaticData() = coroutineScope {
        val metaJob = async(Dispatchers.IO) { runCatching { meta() } }
        val indexJob = async(Dispatchers.IO) { runCatching { pageIndex() } }
        val geometryJob = async(Dispatchers.Default) { runCatching { ensureGeometryLoaded() } }
        metaJob.await(); indexJob.await(); geometryJob.await()
    }

    /**
     * Gives the current page priority. Tooltip/word-by-word JSON is intentionally
     * limited to the reading window; QPC layout/font preloading is handled by the
     * dedicated 604-page startup preloader.
     */
    suspend fun prewarmMushafAround(page: Int) = coroutineScope {
        val center = page.coerceIn(1, 604)
        val pages = listOf(center, center + 1, center - 1, center + 2, center - 2, center + 3, center - 3)
            .filter { it in 1..604 }
            .distinct()

        val staticJob = async { runCatching { prewarmMushafStaticData() } }
        pages.forEach { p ->
            async(Dispatchers.IO) {
                runCatching { page(p) }
                runCatching { wordPage(p) }
            }
        }
        staticJob.await()
    }


    /**
     * Starts loading all 114 local surah JSON files, metadata, page index and
     * Mushaf geometry as soon as the application is alive. The work is bounded
     * and never blocks startup; foreground calls still read directly from the
     * same caches and therefore immediately benefit from whatever is ready.
     */
    fun startQuranTextPreload() {
        if (!quranTextPreloadStarted.compareAndSet(false, true)) return

        preloadScope.launch { runCatching { meta() } }
        preloadScope.launch { runCatching { pageIndex() } }
        preloadScope.launch { runCatching { ensureGeometryLoaded() } }

        // Six workers keep decompression/JSON parsing busy without flooding the
        // phone with 114 simultaneous allocations.
        repeat(6) { worker ->
            preloadScope.launch {
                var id = worker + 1
                while (id <= 114) {
                    runCatching { surah(id) }
                    id += 6
                    kotlinx.coroutines.yield()
                }
            }
        }
    }

    /** Warms all local data needed by one surah without blocking rendering. */
    suspend fun prewarmSurah(surahId: Int) = coroutineScope {
        val data = async(Dispatchers.IO) { runCatching { surah(surahId) }.getOrNull() }
        val s = data.await() ?: return@coroutineScope
        val pages = s.ayahs.map { it.p }.distinct()
        pages.take(4).forEach { p ->
            launch(Dispatchers.IO) { runCatching { wordPage(p) } }
        }
    }

    suspend fun hadrTimings(): List<Pair<Float, Float>> {
        val root = JSONObject(assetText("quran/data/hadr-page-timings.json"))
        val arr = root.getJSONArray("pages")
        return buildList {
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                add(x.optDouble("start").toFloat() to x.optDouble("end").toFloat())
            }
        }
    }
}
