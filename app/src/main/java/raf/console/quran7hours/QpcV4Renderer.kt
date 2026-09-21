package raf.console.quran7hours

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.SoftReference
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Page-specific QPC renderer. V4 Tajweed and V2 plain fonts plus all 604 page layouts
 * are bundled into the application at build time, so Quran rendering never waits for
 * the network at runtime.
 */
data class QpcV4Word(
    val location: String,
    val word: String,
    val glyph: String,
    val page: Int
)

data class QpcV4Line(
    val line: Int,
    val type: String,
    val text: String,
    val surah: Int?,
    val qpcGlyphs: String,
    val words: List<QpcV4Word>,
    val centered: Boolean = false,

    // Exact visual word/glyph units exported by QUL layout 19.
    //
    // These units are authoritative for Mushaf rendering. `words` remains only
    // metadata for taps/translations/audio; it must never be allowed to recompute
    // the physical line break.
    val layoutGlyphs: List<String> = emptyList()
)

data class QpcV4Page(val page: Int, val lines: List<QpcV4Line>)

/**
 * Official QUL/KFGQPC V4 (1441H) physical line definition. QUL layout id 19
 * contains 604 pages and, for normal pages, 15 physical lines. The renderer
 * follows line_number + line_type + is_centered instead of approximating page
 * widths from screenshots/reference geometry.
 */
private data class QulV4LineSpec(
    val lineNumber: Int,
    val lineType: String,
    val centered: Boolean,
    val surahNumber: Int?,
    val firstWordId: Int?,
    val lastWordId: Int?,
    val content: String,
    val glyphs: List<String> = emptyList()
)

private object QulV4LayoutSource {
    /*
     * QUL layout 19 — KFGQPC V4 (1441H), 604 pages / 15 physical rows.
     *
     * New QUL exports are page-by-page JSON files:
     *   { "page": 2, "lines": { "1": {...}, "2": {...} } }
     *
     * The project already used an older all-pages export:
     *   quran/qpc-v4/qul/quran_pages.json
     *
     * Support both. The per-page official JSON is preferred whenever it is bundled.
     */
    private const val MONOLITHIC_ASSET = "quran/qpc-v4/qul/quran_pages.json"
    private val OFFICIAL_PAGE_DIRS = arrayOf(
        "quran/qpc-v4/qul/layout19",
        "quran/qpc-v4/qul/19",
        "quran/qpc-v4/qul/pages"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadStarted = AtomicBoolean(false)
    private val pageCache = ConcurrentHashMap<Int, List<QulV4LineSpec>>()

    @Volatile
    private var monolithic: Map<Int, List<QulV4LineSpec>>? = null

    private var job: kotlinx.coroutines.Deferred<Map<Int, List<QulV4LineSpec>>>? = null

    fun start(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        synchronized(this) {
            if (job == null) {
                job = scope.async { loadMonolithic(app) }
            }
        }
    }

    suspend fun page(context: Context, page: Int): List<QulV4LineSpec>? {
        val p = page.coerceIn(1, 604)
        pageCache[p]?.let { return it }

        val app = context.applicationContext

        // Prefer the current official JSON export if it has been installed.
        loadOfficialPage(app, p)?.let { exact ->
            pageCache[p] = exact
            return exact
        }

        monolithic?.get(p)?.let {
            pageCache[p] = it
            return it
        }

        start(app)
        val current = synchronized(this) { job }
        val loaded = current?.let { runCatching { it.await() }.getOrNull() }.orEmpty()
        val result = loaded[p]
        if (result != null) pageCache[p] = result
        return result
    }

    private fun readGlyphArray(line: JSONObject): List<String> {
        val data = line.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val glyph = data.optString(i).trim()
                if (glyph.isNotBlank()) add(glyph)
            }
        }
    }

    private fun parseOfficialPage(raw: String, expectedPage: Int): List<QulV4LineSpec>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val pageNumber = root.optInt("page", root.optInt("page_number", expectedPage))
        if (pageNumber != expectedPage) return null

        val linesObject = root.optJSONObject("lines") ?: return null
        val keys = buildList {
            val iterator = linesObject.keys()
            while (iterator.hasNext()) {
                iterator.next().toIntOrNull()?.let(::add)
            }
        }.sorted()

        val parsed = buildList {
            keys.forEach { lineNumber ->
                val line = linesObject.optJSONObject(lineNumber.toString()) ?: return@forEach
                val type = line.optString("type", line.optString("line_type", "ayah"))
                val alignment = line.optString("alignment")
                val glyphs = readGlyphArray(line)
                add(
                    QulV4LineSpec(
                        lineNumber = lineNumber,
                        lineType = type,
                        centered = when {
                            alignment.equals("centered", ignoreCase = true) -> true
                            alignment.equals("justified", ignoreCase = true) -> false
                            else -> line.optBoolean("is_centered", false)
                        },
                        surahNumber = line.optInt("surah_number").takeIf { it > 0 },
                        firstWordId = line.optInt("first_word_id").takeIf { it > 0 },
                        lastWordId = line.optInt("last_word_id").takeIf { it > 0 },
                        content = glyphs.joinToString(" "),
                        glyphs = glyphs
                    )
                )
            }
        }

        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun loadOfficialPage(context: Context, page: Int): List<QulV4LineSpec>? {
        val names = listOf(
            "$page.json",
            "page-$page.json",
            "page-${page.toString().padStart(3, '0')}.json"
        )

        for (dir in OFFICIAL_PAGE_DIRS) {
            for (name in names) {
                val path = "$dir/$name"
                val raw = runCatching {
                    context.assets.open(path)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull() ?: continue

                parseOfficialPage(raw, page)?.let { return it }
            }
        }
        return null
    }

    private fun loadMonolithic(context: Context): Map<Int, List<QulV4LineSpec>> {
        monolithic?.let { return it }

        val raw = runCatching {
            context.assets.open(MONOLITHIC_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull() ?: return emptyMap()

        val result = parseMonolithic(raw)
        monolithic = result
        result.forEach { (page, lines) -> pageCache.putIfAbsent(page, lines) }
        return result
    }

    private fun parseMonolithic(raw: String): Map<Int, List<QulV4LineSpec>> {
        val out = LinkedHashMap<Int, List<QulV4LineSpec>>(604)
        val root = JSONTokener(raw).nextValue()

        fun parsePage(obj: JSONObject, fallbackPage: Int? = null) {
            val pageNumber = obj.optInt("page_number", obj.optInt("page", fallbackPage ?: 0))
            if (pageNumber !in 1..604) return

            val array = obj.optJSONArray("lines")
            if (array != null) {
                val parsed = buildList {
                    for (i in 0 until array.length()) {
                        val line = array.optJSONObject(i) ?: continue
                        val lineNumber = line.optInt("line_number", line.optInt("line", i + 1))
                        if (lineNumber !in 1..15) continue

                        val glyphs = readGlyphArray(line)
                        val alignment = line.optString("alignment")
                        add(
                            QulV4LineSpec(
                                lineNumber = lineNumber,
                                lineType = line.optString(
                                    "line_type",
                                    line.optString("type", "ayah")
                                ),
                                centered = when {
                                    alignment.equals("centered", true) -> true
                                    alignment.equals("justified", true) -> false
                                    else -> line.optBoolean("is_centered", false)
                                },
                                surahNumber = line.optInt("surah_number").takeIf { it > 0 },
                                firstWordId = line.optInt("first_word_id").takeIf { it > 0 },
                                lastWordId = line.optInt("last_word_id").takeIf { it > 0 },
                                content = line.optString("content").ifBlank {
                                    glyphs.joinToString(" ")
                                },
                                glyphs = glyphs
                            )
                        )
                    }
                }.sortedBy { it.lineNumber }

                if (parsed.isNotEmpty()) out[pageNumber] = parsed
                return
            }

            // Also accept the new QUL object-shaped "lines" representation when
            // somebody concatenates page JSON files into one asset.
            val lineObj = obj.optJSONObject("lines") ?: return
            val keys = buildList {
                val iterator = lineObj.keys()
                while (iterator.hasNext()) iterator.next().toIntOrNull()?.let(::add)
            }.sorted()

            val parsed = buildList {
                keys.forEach { lineNumber ->
                    val line = lineObj.optJSONObject(lineNumber.toString()) ?: return@forEach
                    val glyphs = readGlyphArray(line)
                    val alignment = line.optString("alignment")
                    add(
                        QulV4LineSpec(
                            lineNumber = lineNumber,
                            lineType = line.optString("type", line.optString("line_type", "ayah")),
                            centered = when {
                                alignment.equals("centered", true) -> true
                                alignment.equals("justified", true) -> false
                                else -> line.optBoolean("is_centered", false)
                            },
                            surahNumber = line.optInt("surah_number").takeIf { it > 0 },
                            firstWordId = line.optInt("first_word_id").takeIf { it > 0 },
                            lastWordId = line.optInt("last_word_id").takeIf { it > 0 },
                            content = line.optString("content").ifBlank {
                                glyphs.joinToString(" ")
                            },
                            glyphs = glyphs
                        )
                    )
                }
            }
            if (parsed.isNotEmpty()) out[pageNumber] = parsed
        }

        when (root) {
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    root.optJSONObject(i)?.let { parsePage(it) }
                }
            }

            is JSONObject -> {
                val pages = root.optJSONArray("pages")
                if (pages != null) {
                    for (i in 0 until pages.length()) {
                        pages.optJSONObject(i)?.let { parsePage(it) }
                    }
                } else {
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        root.optJSONObject(key)?.let { parsePage(it, key.toIntOrNull()) }
                    }
                }
            }
        }

        return out
    }
}

private object QpcV4Source {
    private val pages = ConcurrentHashMap<Int, QpcV4Page>()

    // A small strong LRU keeps the active reading window pinned. The soft cache
    // can retain the rest of the background-preloaded page fonts when memory is
    // available without forcing Android to keep ~1,200 Typefaces alive forever.
    private const val HOT_FONT_LIMIT = 40
    private val hotFonts = object : LinkedHashMap<String, Typeface>(HOT_FONT_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean =
            size > HOT_FONT_LIMIT
    }
    private val warmFonts = ConcurrentHashMap<String, SoftReference<Typeface>>()

    // Prevent the foreground request and startup preloader from parsing/creating
    // the same page twice. At most a handful of jobs exist at once.
    private val pageJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Deferred<QpcV4Page>>()
    private val fontJobs = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Typeface>>()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val layoutPreloadStarted = AtomicBoolean(false)
    @Volatile private var offlinePackReady: Boolean? = null

    private fun packReady(context: Context): Boolean {
        offlinePackReady?.let { return it }
        val ready = runCatching {
            context.applicationContext.assets.open(
                "quran/qpc-v4/OFFLINE_ASSETS_READY.txt"
            ).use { input ->
                input.bufferedReader(Charsets.UTF_8).readText().contains("layouts=604")
            }
        }.getOrDefault(false)
        offlinePackReady = ready
        return ready
    }

    private fun layoutAsset(page: Int) =
        "quran/qpc-v4/layout/page-${page.toString().padStart(3, '0')}.json"

    private fun fontAsset(page: Int, tajweed: Boolean) =
        "quran/qpc-v4/fonts/${if (tajweed) "v4" else "v2"}/p$page.ttf"

    private fun fontKey(page: Int, tajweed: Boolean) =
        "$page:${if (tajweed) "v4" else "v2"}"

    private fun hotFont(key: String): Typeface? = synchronized(hotFonts) { hotFonts[key] }

    private fun rememberFont(key: String, typeface: Typeface): Typeface {
        synchronized(hotFonts) { hotFonts[key] = typeface }
        warmFonts[key] = SoftReference(typeface)
        return typeface
    }

    /** Loads one page layout from local uncompressed APK assets exactly once. */
    suspend fun page(context: Context, page: Int): QpcV4Page {
        val p = page.coerceIn(1, 604)
        pages[p]?.let { return it }

        val app = context.applicationContext
        val job = pageJobs.computeIfAbsent(p) {
            loadScope.async {
                val raw = app.assets.open(layoutAsset(p))
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val legacy = parsePage(raw, p)
                val qul = QulV4LayoutSource.page(app, p)
                val resolved = if (!qul.isNullOrEmpty()) applyQulLayout(legacy, qul) else legacy
                resolved.also { pages[p] = it }
            }
        }
        return try {
            job.await()
        } finally {
            if (job.isCompleted) pageJobs.remove(p, job)
        }
    }

    /**
     * Tajweed ON  -> QPC/QCF V4 colour font.
     * Tajweed OFF -> QPC/QCF V2 plain font.
     *
     * TTF/OTF assets are stored uncompressed in the APK, so createFromAsset()
     * memory-maps local data; no extraction/network step happens here.
     */
    suspend fun typeface(context: Context, page: Int, tajweed: Boolean): Typeface {
        val p = page.coerceIn(1, 604)
        val key = fontKey(p, tajweed)
        hotFont(key)?.let { return it }
        warmFonts[key]?.get()?.let { return rememberFont(key, it) }

        val app = context.applicationContext
        val job = fontJobs.computeIfAbsent(key) {
            loadScope.async {
                Typeface.createFromAsset(app.assets, fontAsset(p, tajweed))
                    .let { rememberFont(key, it) }
            }
        }
        return try {
            job.await()
        } finally {
            if (job.isCompleted) fontJobs.remove(key, job)
        }
    }

    fun cached(page: Int, tajweed: Boolean): Pair<QpcV4Page, Typeface>? {
        val p = page.coerceIn(1, 604)
        val key = fontKey(p, tajweed)
        val pg = pages[p] ?: return null
        val tf = hotFont(key) ?: warmFonts[key]?.get()?.let { rememberFont(key, it) } ?: return null
        return pg to tf
    }

    /**
     * Begins parsing all 604 lightweight page-layout JSON files after startup.
     * Only two workers are used and they yield after each page, so a foreground
     * request never sits behind hundreds of speculative reads.
     */
    fun startLayoutPreload(context: Context) {
        val app = context.applicationContext
        // A partial QPC install used to make startup slower by throwing hundreds
        // of AssetManager exceptions while the app probed missing pages. Full
        // background preloading is enabled only after the installer has verified
        // all 604 layouts + V4 + V2 fonts and written the ready marker.
        if (!packReady(app)) return
        if (!layoutPreloadStarted.compareAndSet(false, true)) return
        // QUL ships the complete 604-page geometry in one compact JSON. Parse it
        // once at application startup. Do NOT simultaneously parse 604 legacy
        // metadata files: that old warm-up competed with the foreground page and
        // was one of the causes of slow first-open behaviour. Word metadata is
        // loaded lazily only for the active page and its small prewarm window.
        QulV4LayoutSource.start(app)
    }

    /**
     * Deliberately do NOT instantiate all 604/1208 Typefaces at startup. Android
     * font creation is far heavier than reading the local JSON and used to starve
     * the page the user opened. Fonts are already bundled in the APK; the active
     * page and its neighbours are created on demand by prewarmQpcAround().
     */
    fun startFontPreload(context: Context, tajweed: Boolean) {
        // Keep both page-1 font families hot. Bismillah rendering reuses the
        // verified page-1 QPC glyphs on every surah, so a Tajweed toggle must not
        // wait for a new font mapping before repainting the Bismillah.
        val app = context.applicationContext
        loadScope.launch {
            runCatching { typeface(app, 1, true) }
            runCatching { typeface(app, 1, false) }
        }
    }

    private fun parsePage(raw: String, fallbackPage: Int): QpcV4Page {
        val root = JSONObject(raw)
        val page = root.optInt("page", fallbackPage).coerceIn(1, 604)
        val linesArray = root.optJSONArray("lines")
        val lines = buildList {
            if (linesArray != null) for (i in 0 until linesArray.length()) {
                val line = linesArray.optJSONObject(i) ?: continue
                val wordsArray = line.optJSONArray("words")
                val words = buildList {
                    if (wordsArray != null) for (j in 0 until wordsArray.length()) {
                        val w = wordsArray.optJSONObject(j) ?: continue
                        val location = w.optString("location")
                        if (location.isBlank()) continue
                        add(QpcV4Word(location, w.optString("word"), w.optString("qpcV2"), page))
                    }
                }
                add(
                    QpcV4Line(
                        line = line.optInt("line", i + 1),
                        type = line.optString("type", "text"),
                        text = line.optString("text"),
                        surah = line.optString("surah").toIntOrNull(),
                        qpcGlyphs = line.optString("qpcV2"),
                        words = words,
                        centered = when {
                            line.has("is_centered") -> line.optBoolean("is_centered", false)
                            line.optString("alignment").equals("centered", ignoreCase = true) -> true
                            line.optString("alignment").equals("justified", ignoreCase = true) -> false
                            page <= 2 -> true
                            line.optString("type", "text") == "basmala" -> true
                            else -> false
                        }
                    )
                )
            }
        }
        return QpcV4Page(page, lines)
    }

    private fun normalizedGlyphs(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            if (!ch.isWhitespace() && ch != '\u200e' && ch != '\u200f' && ch != '\u061c' && ch != '\ufeff') append(ch)
        }
    }

    /**
     * QUL owns the physical page geometry; the already bundled per-page JSON is
     * retained only as word metadata for taps/translations. The V4 glyph stream
     * is identical, so most ayah lines can be associated without changing QUL's
     * line breaks. If a line cannot be associated safely, visual rendering still
     * uses QUL's exact `content` and only per-word taps are omitted for that line.
     */
    private fun splitQulContent(content: String): List<String> {
        val clean = content
            .replace("\u200e", "")
            .replace("\u200f", "")
            .replace("\u061c", "")
            .replace("\ufeff", "")
            .trim()

        if (clean.isBlank()) return emptyList()

        val words = clean.split(Regex("\\s+")).filter(String::isNotBlank)
        return if (words.size > 1) words else listOf(clean)
    }

    /**
     * QUL layout 19 is immutable page geometry.
     *
     * The renderer is NOT allowed to:
     * - move a word to another row;
     * - derive a new line break;
     * - squeeze/stretch a QCF word;
     * - replace a long kaf/lam/meem with Unicode text.
     *
     * `line_number`, line type and alignment come directly from QUL. The visible
     * glyph stream also comes directly from QUL when the official JSON contains
     * `data`; the existing local page JSON is retained only for interaction metadata.
     */
    private fun applyQulLayout(
        legacy: QpcV4Page,
        specs: List<QulV4LineSpec>
    ): QpcV4Page {
        val legacyAyahWords = legacy.lines
            .filterNot {
                it.type == "surah-header" ||
                        it.type == "surah_name" ||
                        it.type == "basmala" ||
                        it.type == "basmallah"
            }
            .flatMap { it.words }

        var cursor = 0

        fun consumeMetadata(spec: QulV4LineSpec): List<QpcV4Word> {
            val source = when {
                spec.glyphs.isNotEmpty() -> spec.glyphs.joinToString("")
                else -> spec.content
            }
            val target = normalizedGlyphs(source)

            if (target.isBlank() || cursor >= legacyAyahWords.size) return emptyList()

            /*
             * This search is metadata-only. It does not control visual layout.
             * A failed match merely disables per-word tap metadata for that line;
             * the exact QUL visual line remains untouched.
             */
            val maxProbe = min(legacyAyahWords.lastIndex, cursor + 8)
            for (start in cursor..maxProbe) {
                val acc = StringBuilder()
                var end = start

                while (end < legacyAyahWords.size && acc.length <= target.length + 16) {
                    acc.append(normalizedGlyphs(legacyAyahWords[end].glyph))
                    end++

                    val current = acc.toString()
                    if (current == target) {
                        cursor = end
                        return legacyAyahWords.subList(start, end)
                    }
                    if (!target.startsWith(current)) break
                }
            }

            return emptyList()
        }

        val mapped = specs
            .filter { it.lineNumber in 1..15 }
            .sortedBy { it.lineNumber }
            .map { spec ->
                val type = when (spec.lineType.lowercase()) {
                    "surah_name", "surah-name", "surah_header", "surah-header", "surah" ->
                        "surah-header"

                    "basmallah", "basmala", "bismillah" ->
                        "basmala"

                    else ->
                        "text"
                }

                val legacySameLine = legacy.lines.firstOrNull { it.line == spec.lineNumber }
                val metadata = if (type == "text") consumeMetadata(spec) else emptyList()

                val exactGlyphs = when {
                    spec.glyphs.isNotEmpty() ->
                        spec.glyphs.filter(String::isNotBlank)

                    spec.content.isNotBlank() -> {
                        val units = splitQulContent(spec.content)

                        /*
                         * Old all-pages exports sometimes stored the complete QCF
                         * stream without whitespace. If metadata matched exactly,
                         * retain its word boundaries while keeping the same glyphs.
                         */
                        if (
                            units.size == 1 &&
                            metadata.isNotEmpty() &&
                            normalizedGlyphs(metadata.joinToString("") { it.glyph }) ==
                            normalizedGlyphs(spec.content)
                        ) {
                            metadata.map { it.glyph }
                        } else {
                            units
                        }
                    }

                    metadata.isNotEmpty() ->
                        metadata.map { it.glyph }

                    else ->
                        splitQulContent(legacySameLine?.qpcGlyphs.orEmpty())
                }

                QpcV4Line(
                    line = spec.lineNumber,
                    type = type,
                    text = legacySameLine?.text.orEmpty(),
                    surah = spec.surahNumber ?: legacySameLine?.surah,
                    qpcGlyphs = exactGlyphs.joinToString(" "),
                    words = metadata,
                    centered = spec.centered,
                    layoutGlyphs = exactGlyphs
                )
            }

        return QpcV4Page(
            page = legacy.page,
            lines = mapped
        )
    }
}

/**
 * Starts the full offline Mushaf warm-up. This is intentionally fire-and-forget:
 * application startup is never blocked by 604-page preloading.
 */
fun startQpcStartupPreload(context: Context, tajweed: Boolean) {
    QpcV4Source.startLayoutPreload(context.applicationContext)
    QpcV4Source.startFontPreload(context.applicationContext, tajweed)
}

/** Starts warming the newly selected font family after a Tajweed toggle. */
fun startQpcFontPreload(context: Context, tajweed: Boolean) {
    QpcV4Source.startFontPreload(context.applicationContext, tajweed)
}

/**
 * Gives the requested page foreground priority and warms its immediate reading
 * window. The current layout+font are loaded concurrently instead of serially.
 */
suspend fun prewarmQpcAround(context: Context, page: Int, tajweed: Boolean) {
    val app = context.applicationContext
    val p = page.coerceIn(1, 604)

    /*
     * Tajweed switching must be a pure repaint, not a "load another font while
     * the user flips pages" operation. Keep BOTH QPC font families ready for the
     * visible page and a generous ViewPager reading window around it.
     *
     * 13 pages × 2 font families = 26 hot mappings, still below HOT_FONT_LIMIT=40.
     * As the pager settles on another page this window moves with it.
     */
    val window = buildList {
        add(p)
        for (distance in 1..6) {
            if (p + distance <= 604) add(p + distance)
            if (p - distance >= 1) add(p - distance)
        }
    }.distinct()

    coroutineScope {
        window.forEach { pg ->
            launch(Dispatchers.IO) {
                coroutineScope {
                    val layout = async { runCatching { QpcV4Source.page(app, pg) } }
                    val v4 = async { runCatching { QpcV4Source.typeface(app, pg, true) } }
                    val v2 = async { runCatching { QpcV4Source.typeface(app, pg, false) } }
                    layout.await()
                    v4.await()
                    v2.await()
                }
            }
        }
    }
}

private fun canonicalToAppKey(location: String): String? {
    val bits = location.split(':').mapNotNull(String::toIntOrNull)
    val s = bits.getOrNull(0) ?: return null
    val a = bits.getOrNull(1) ?: return null
    val w = bits.getOrNull(2) ?: 1
    if (s != 1) return "$s:$a"
    return when {
        a == 1 -> "bismillah"
        a in 2..6 -> "1:${a - 1}"
        a == 7 -> if (w <= 4) "1:6" else "1:7"
        else -> null
    }
}

private fun verseNumberWord(word: String): Boolean = Regex("[٠-٩۰-۹]\\s*$").containsMatchIn(word)
private fun stripVerseNumberGlyph(glyph: String, word: String): String {
    if (!verseNumberWord(word)) return glyph
    val pieces = glyph.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return if (pieces.size > 1) pieces.dropLast(1).joinToString(" ") else glyph
}

private fun splitQpcGlyph(glyph: String, word: String): Pair<String, String> {
    val raw = glyph.trim()
    if (raw.isBlank() || !verseNumberWord(word)) return raw to ""
    val pieces = raw.split(Regex("\\s+")).filter(String::isNotBlank)
    if (pieces.size < 2) return raw to ""
    return pieces.dropLast(1).joinToString(" ") to pieces.last()
}

suspend fun loadQpcV4Ayah(context: Context, surah: Int, ayah: Int, page: Int): List<QpcV4Word> {
    val pages = buildList { add(page.coerceIn(1, 604)); if (page < 604) add(page + 1) }
    val out = mutableListOf<QpcV4Word>()
    pages.forEach { p ->
        QpcV4Source.page(context, p).lines.forEach { line ->
            line.words.forEach { word ->
                if (canonicalToAppKey(word.location) == "$surah:$ayah") {
                    out += word.copy(glyph = stripVerseNumberGlyph(word.glyph, word.word))
                }
            }
        }
    }
    return out.distinctBy { "${it.page}:${it.location}" }
}

suspend fun loadQpcV4Bismillah(context: Context): List<QpcV4Word> =
    QpcV4Source.page(context, 1).lines.flatMap { it.words }.filter { canonicalToAppKey(it.location) == "bismillah" }
        .map { it.copy(glyph = stripVerseNumberGlyph(it.glyph, it.word)) }

private class QpcTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = typeface }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = typeface }
}

private data class QpcPrepared(val words: List<QpcV4Word>, val typefaces: Map<Int, Typeface>)

@Composable
fun QpcV4AyahText(
    m: AdaptiveMetrics,
    surah: Int,
    ayah: Int,
    page: Int,
    settings: AppSettings,
    fallback: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val prepared by produceState<QpcPrepared?>(null, surah, ayah, page, settings.tajweed) {
        value = runCatching {
            val words = loadQpcV4Ayah(context.applicationContext, surah, ayah, page)
            require(words.isNotEmpty())
            val typefaces = words.map { it.page }.distinct().associateWith { QpcV4Source.typeface(context.applicationContext, it, settings.tajweed) }
            QpcPrepared(words, typefaces)
        }.getOrNull()
    }
    val data = prepared
    if (data == null) {
        Text(
            text = fallback,
            modifier = modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontFamily = QuranFont,
            fontSize = m.arabicBase * settings.arabicScale,
            lineHeight = (m.arabicBase * settings.arabicScale) * settings.lineHeight
        )
        return
    }
    val builder = SpannableStringBuilder()
    data.words.forEachIndexed { index, word ->
        if (index > 0) builder.append(' ')
        val start = builder.length
        builder.append(word.glyph)
        val end = builder.length
        data.typefaces[word.page]?.let { builder.setSpan(QpcTypefaceSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    QpcTextView(
        text = builder,
        fontSize = m.arabicBase * settings.arabicScale,
        textColor = ink,
        lineMultiplier = settings.lineHeight,
        singleLine = false,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun QpcV4BismillahText(m: AdaptiveMetrics, settings: AppSettings, modifier: Modifier = Modifier, fixedHeight: Dp? = null) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val prepared by produceState<QpcPrepared?>(null, settings.tajweed) {
        value = runCatching {
            val words = loadQpcV4Bismillah(context.applicationContext)
            val tf = QpcV4Source.typeface(context.applicationContext, 1, settings.tajweed)
            QpcPrepared(words, mapOf(1 to tf))
        }.getOrNull()
    }
    val data = prepared
    if (data == null || data.words.isEmpty()) {
        Text(
            "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
            modifier = modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontFamily = QuranFont,
            fontSize = m.arabicBase * settings.arabicScale,
            color = MaterialTheme.colorScheme.onSurface
        )
        return
    }
    val b = SpannableStringBuilder(data.words.joinToString(" ") { it.glyph })
    b.setSpan(QpcTypefaceSpan(data.typefaces.getValue(1)), 0, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    QpcTextView(b, m.arabicBase * settings.arabicScale, ink, settings.lineHeight, false, modifier.fillMaxWidth(), fixedHeight = fixedHeight)
}

@Composable
fun QpcV4SurahBismillahText(m: AdaptiveMetrics, page: Int, settings: AppSettings, modifier: Modifier = Modifier) {
    // The layout-level qpcV2 basmala ligature is not a V4 word sequence.
    // Reuse the verified page-1 V4 basmala glyphs exactly as the web app does.
    QpcV4BismillahText(m, settings, modifier)
}

@Composable
fun QpcV4WordByWordAyah(
    m: AdaptiveMetrics,
    surah: Int,
    ayah: Int,
    page: Int,
    words: List<WordItem>,
    settings: AppSettings
) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val prepared by produceState<QpcPrepared?>(null, surah, ayah, page, settings.tajweed) {
        value = runCatching {
            val glyphWords = loadQpcV4Ayah(context.applicationContext, surah, ayah, page)
            val typefaces = glyphWords.map { it.page }.distinct().associateWith { QpcV4Source.typeface(context.applicationContext, it, settings.tajweed) }
            QpcPrepared(glyphWords, typefaces)
        }.getOrNull()
    }
    val data = prepared
    if (data == null || data.words.isEmpty()) {
        WordByWordFallback(m, words, settings)
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(m.md, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(m.md)
        ) {
            data.words.forEachIndexed { index, glyphWord ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val ss = SpannableStringBuilder(glyphWord.glyph)
                    data.typefaces[glyphWord.page]?.let { ss.setSpan(QpcTypefaceSpan(it), 0, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    QpcTextView(ss, m.arabicBase * settings.arabicScale, ink, 1.18f, true, Modifier)
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Text(
                            words.getOrNull(index)?.translation.orEmpty(),
                            fontSize = (m.arabicBase * settings.arabicScale) * settings.wordByWordScale,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordByWordFallback(m: AdaptiveMetrics, words: List<WordItem>, settings: AppSettings) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.md, Alignment.End), verticalArrangement = Arrangement.spacedBy(m.md)) {
            words.forEach { w ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(w.arabic, fontFamily = QuranFont, fontSize = m.arabicBase * settings.arabicScale, textAlign = TextAlign.Center)
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Text(w.translation, fontSize = (m.arabicBase * settings.arabicScale) * settings.wordByWordScale, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun QpcV4MushafPage(
    m: AdaptiveMetrics,
    page: Int,
    repository: QuranRepository,
    settings: AppSettings,
    audio: QuranAudioController,
    onTip: (String) -> Unit,
    onAyahSelected: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    fallbackContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = LocalQuranColors.current
    val track by audio.track.collectAsState()
    val quranMeta by produceState<QuranMeta?>(null) { value = runCatching { repository.meta() }.getOrNull() }

    // The physical page geometry comes from QUL layout id 19. The old
    // mushaf-reference-geometry.json is deliberately not consulted here.
    /*
     * IMPORTANT: produceState keeps its State object when keys change. The old
     * implementation only loaded when `value == null`, so after changing
     * settings.tajweed the State still contained the previous Typeface and the
     * new font was not applied until the pager recreated that page. That is why
     * Tajweed appeared to switch "gradually" after several page flips.
     *
     * Always replace the payload for every (page, tajweed) key. In the normal
     * path the requested V2/V4 Typeface is already in the dual-family hot window,
     * so this assignment happens immediately without a visible loading phase.
     */
    val payload by produceState<Result<Pair<QpcV4Page, Typeface>>?>(null, page, settings.tajweed) {
        val cached = QpcV4Source.cached(page, settings.tajweed)
        value = if (cached != null) {
            Result.success(cached)
        } else {
            runCatching {
                coroutineScope {
                    val pageJob = async(Dispatchers.IO) {
                        QpcV4Source.page(context.applicationContext, page)
                    }
                    val fontJob = async(Dispatchers.IO) {
                        QpcV4Source.typeface(context.applicationContext, page, settings.tajweed)
                    }
                    pageJob.await() to fontJob.await()
                }
            }
        }
    }
    val translations by produceState<Map<String, String>>(emptyMap(), page) {
        value = runCatching {
            repository.wordPage(page).words.associate { it.location to it.translation }
        }.getOrDefault(emptyMap())
    }
    val bismillahPayload by produceState<Pair<List<QpcV4Word>, Typeface>?>(null, settings.tajweed) {
        val cachedTypeface = QpcV4Source.cached(1, settings.tajweed)?.second
        value = runCatching {
            val words = loadQpcV4Bismillah(context.applicationContext)
            val font = cachedTypeface
                ?: QpcV4Source.typeface(context.applicationContext, 1, settings.tajweed)
            words to font
        }.getOrNull()
    }

    if (payload == null) {
        Text(
            "Открываем страницу…",
            modifier = Modifier.fillMaxWidth().padding(m.xs),
            textAlign = TextAlign.Center,
            color = colors.mushafMuted,
            fontSize = m.bodySmall
        )
        return
    }
    val data = payload?.getOrNull()
    if (data == null) {
        fallbackContent()
        return
    }
    var (qpcPage, typeface) = data

    val fatihaMarkers = if (page == 1) buildMap<Int, String> {
        qpcPage.lines.flatMap { it.words }.forEach { word ->
            val bits = word.location.split(':').mapNotNull(String::toIntOrNull)
            val canonicalSurah = bits.getOrNull(0) ?: return@forEach
            val canonicalAyah = bits.getOrNull(1) ?: return@forEach
            if (canonicalSurah == 1 && verseNumberWord(word.word)) {
                val marker = splitQpcGlyph(word.glyph, word.word).second
                if (marker.isNotBlank()) put(canonicalAyah, marker)
            }
        }
    } else emptyMap()

    fun activeKey(key: String): Boolean {
        if (key == "bismillah" || key.startsWith("bismillah:")) {
            val targetSurah = key.substringAfter(':', "1").toIntOrNull() ?: 1
            return track?.kind == TrackKind.BISMILLAH && track?.surah == targetSurah
        }
        val surah = key.substringBefore(':').toIntOrNull() ?: return false
        val ayah = key.substringAfter(':').toIntOrNull() ?: return false
        return track?.kind == TrackKind.AYAH && track?.surah == surah &&
                ayah in (track?.ayah ?: -1)..(track?.endAyah ?: -1)
    }

    fun clickSegment(segment: MushafGlyphSegment) {
        if (segment.translation.isNotBlank()) onTip(segment.translation)
        val key = segment.key ?: return
        if (key == "bismillah" || key.startsWith("bismillah:")) {
            val targetSurah = key.substringAfter(':', "1").toIntOrNull() ?: 1
            val targetName = quranMeta?.surahs?.getOrNull(targetSurah - 1)?.nameRu ?: "Сура $targetSurah"
            audio.playBismillah(targetSurah, targetName)
        } else {
            val surah = key.substringBefore(':').toIntOrNull() ?: return
            val ayah = key.substringAfter(':').toIntOrNull() ?: return
            onAyahSelected(surah, ayah)
            audio.playAyah(surah, ayah, quranMeta?.surahs?.getOrNull(surah - 1)?.nameRu ?: "Сура $surah")
        }
    }

    fun segmentsFor(line: QpcV4Line): List<MushafGlyphSegment> {
        /*
         * Exact QUL glyphs are the visual source of truth. Metadata is attached
         * only when there is an unambiguous one-to-one correspondence.
         */
        if (line.layoutGlyphs.isNotEmpty()) {
            val exact = line.layoutGlyphs.filter(String::isNotBlank)

            if (exact.size == line.words.size && exact.isNotEmpty()) {
                return buildList {
                    exact.forEachIndexed { index, glyph ->
                        val word = line.words[index]
                        val key = canonicalToAppKey(word.location)
                        val translation = translations[word.location].orEmpty()

                        /*
                         * In QCF V4 a verse-ending medallion may be encoded together
                         * with the final word glyph. Do not split/re-shape it here:
                         * QUL's exported glyph unit must stay byte-for-byte intact.
                         */
                        add(
                            MushafGlyphSegment(
                                glyph = glyph,
                                key = key,
                                translation = translation,
                                active = key?.let(::activeKey) == true,
                                isAyahMarker = false
                            )
                        )
                    }
                }
            }

            // Exact rendering wins over interaction metadata if counts differ.
            return exact.map { MushafGlyphSegment(glyph = it) }
        }

        // Compatibility fallback for an older local asset without QUL glyph data.
        if (line.words.isEmpty()) {
            return splitGlyphUnits(line.qpcGlyphs).map { MushafGlyphSegment(glyph = it) }
        }

        return buildList {
            line.words.forEach { word ->
                val key = canonicalToAppKey(word.location)
                val translation = translations[word.location].orEmpty()
                val (wordGlyph, originalMarker) = splitQpcGlyph(word.glyph, word.word)

                if (wordGlyph.isNotBlank()) {
                    add(
                        MushafGlyphSegment(
                            glyph = wordGlyph,
                            key = key,
                            translation = translation,
                            active = key?.let(::activeKey) == true
                        )
                    )
                }

                if (originalMarker.isNotBlank() && key != null && key != "bismillah") {
                    add(
                        MushafGlyphSegment(
                            glyph = originalMarker,
                            key = key,
                            active = activeKey(key),
                            isAyahMarker = true
                        )
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current

        /*
         * DEVICE-ADAPTIVE QUL PAGE GEOMETRY
         * ---------------------------------
         * QUL layout 19 owns the line breaks. The device only decides how large the
         * already-defined page can be drawn inside the available Mushaf viewport.
         *
         * Pages 3..604:
         * - exactly 15 equal-height physical rows;
         * - the grid always occupies the complete available height;
         * - one common font size is used for every Quran row on the page;
         * - the common size is reduced only when the widest REAL glyph ink would not
         *   fit horizontally.
         *
         * Pages 1..2 are the printed opening spread. QUL contains only the visible
         * opening rows there (8 rows in the supplied layout), and all Quran rows are
         * centered. Spreading those rows over 15 slots would push the opening into the
         * upper half of the screen, so the opening uses its own equal-height row grid.
         * This preserves the characteristic rounded silhouette.
         */
        val sortedLines = qpcPage.lines.sortedBy { it.line }
        val openingPage = page <= 2
        val physicalSlots = if (openingPage) {
            sortedLines.maxOfOrNull { it.line }?.coerceAtLeast(1) ?: 8
        } else {
            15
        }

        // Small page frame only. Glyph overhang protection is handled again inside
        // QpcMushafLineView using the exact same safety model.
        val outerHorizontalInset = maxWidth * .012f
        val contentWidth = (maxWidth - outerHorizontalInset * 2f)
            .coerceAtLeast(maxWidth * .80f)
        val contentWidthPx = with(density) { contentWidth.toPx() }.coerceAtLeast(1f)
        val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val slotHeightPx = viewportHeightPx / physicalSlots.toFloat()

        /*
         * Width normally determines the familiar Mushaf text size. Height is only a
         * hard ceiling so unusually short devices, split-screen and landscape can
         * never clip harakat vertically. This also keeps the text size stable when
         * there is ordinary spare vertical room.
         */
        val widthDrivenFontPx = contentWidthPx * if (openingPage) .078f else .064f
        val heightDrivenFontPx = slotHeightPx * if (openingPage) .58f else .62f
        val candidateFontPx = min(widthDrivenFontPx, heightDrivenFontPx).coerceAtLeast(1f)

        val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typeface
            textSize = candidateFontPx
            textScaleX = 1f
            isAntiAlias = true
            isSubpixelText = true
        }

        fun measuredInkWidth(segments: List<MushafGlyphSegment>, gapPx: Float): Float {
            if (segments.isEmpty()) return 0f

            val bounds = Rect()
            var cursorRight = 0f
            var minInk = Float.POSITIVE_INFINITY
            var maxInk = Float.NEGATIVE_INFINITY

            segments.forEachIndexed { index, segment ->
                val glyph = segment.glyph
                val advance = fitPaint.measureText(glyph).coerceAtLeast(0f)
                val drawX = cursorRight - advance

                bounds.setEmpty()
                if (glyph.isNotEmpty()) {
                    fitPaint.getTextBounds(glyph, 0, glyph.length, bounds)
                }

                minInk = min(minInk, drawX + bounds.left.toFloat())
                maxInk = max(maxInk, drawX + bounds.right.toFloat())

                cursorRight = drawX
                if (index != segments.lastIndex) cursorRight -= gapPx
            }

            return if (minInk.isFinite() && maxInk.isFinite()) {
                (maxInk - minInk).coerceAtLeast(0f)
            } else {
                0f
            }
        }

        // Centered opening rows keep a visible natural word gap. Rectangular pages
        // are measured with the minimum safe gap; any remaining width is distributed
        // by the Canvas renderer as justification spacing.
        val fitGapPx = candidateFontPx * if (openingPage) .10f else .035f
        val widestQuranLinePx = sortedLines
            .filter { it.type != "surah-header" }
            .maxOfOrNull { line -> measuredInkWidth(segmentsFor(line), fitGapPx) }
            ?: 0f

        // Same formula is used inside QpcMushafLineView. The extra 0.99 factor covers
        // integer glyph bounds / anti-aliasing rounding differences across Android GPUs.
        val pageSafetyPx = max(candidateFontPx * .18f, contentWidthPx * .014f)
        val printableWidthPx = (contentWidthPx - pageSafetyPx * 2f).coerceAtLeast(1f)
        val widthScale = if (widestQuranLinePx > printableWidthPx && widestQuranLinePx > 0f) {
            (printableWidthPx / widestQuranLinePx).coerceIn(.01f, 1f)
        } else {
            1f
        }
        val fittedFontPx = (candidateFontPx * widthScale * .99f).coerceAtLeast(1f)
        val lineFontSize = with(density) { fittedFontPx.toSp() }

        val lineBySlot = sortedLines.associateBy { line ->
            line.line.coerceIn(1, physicalSlots)
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = outerHorizontalInset)
            ) {
                for (slot in 1..physicalSlots) {
                    val line = lineBySlot[slot]
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)

                    if (line == null) {
                        Box(rowModifier)
                        continue
                    }

                    when (line.type) {
                        "surah-header" -> {
                            val surahId = line.surah
                            val surah = surahId?.let { id -> quranMeta?.surahs?.firstOrNull { it.id == id } }
                            Box(rowModifier, contentAlignment = Alignment.Center) {
                                surah?.let { sm ->
                                    androidx.compose.material3.Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(m.corner * .18f),
                                        color = androidx.compose.ui.graphics.Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(m.xs * .055f, colors.mushafLineStrong),
                                        modifier = Modifier.fillMaxWidth(if (openingPage) .74f else .94f)
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth().padding(horizontal = m.sm, vertical = m.xs * .10f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = sm.nameAr,
                                                fontFamily = QuranFont,
                                                fontSize = lineFontSize * .54f,
                                                color = colors.mushafInk,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        "basmala" -> {
                            val ready = bismillahPayload
                            val nextQuranLine = sortedLines.firstOrNull { candidate ->
                                candidate.line > line.line && candidate.type != "surah-header" && candidate.type != "basmala"
                            }
                            val basmalaSurah = line.surah
                                ?: nextQuranLine?.surah
                                ?: nextQuranLine?.words?.firstOrNull()?.location?.substringBefore(':')?.toIntOrNull()
                                ?: 1
                            val basmalaKey = "bismillah:$basmalaSurah"
                            Box(rowModifier, contentAlignment = Alignment.Center) {
                                val exactBasmala = line.layoutGlyphs
                                    .filter(String::isNotBlank)
                                    .map {
                                        MushafGlyphSegment(
                                            glyph = it,
                                            key = basmalaKey,
                                            active = activeKey(basmalaKey)
                                        )
                                    }

                                if (exactBasmala.isNotEmpty()) {
                                    QpcMushafCanvasLine(
                                        segments = exactBasmala,
                                        typeface = typeface,
                                        centered = true,
                                        fontSize = lineFontSize,
                                        textColor = colors.mushafInk.toArgb(),
                                        onSegmentClick = ::clickSegment,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (ready != null && ready.first.isNotEmpty()) {
                                    val segments = ready.first.map { word ->
                                        val glyph = stripVerseNumberGlyph(word.glyph, word.word)
                                        MushafGlyphSegment(
                                            glyph = glyph,
                                            key = basmalaKey,
                                            active = activeKey(basmalaKey)
                                        )
                                    }.filter { it.glyph.isNotBlank() }

                                    QpcMushafCanvasLine(
                                        segments = segments,
                                        typeface = ready.second,
                                        centered = true,
                                        fontSize = lineFontSize,
                                        textColor = colors.mushafInk.toArgb(),
                                        onSegmentClick = ::clickSegment,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
                                        fontFamily = QuranFont,
                                        fontSize = lineFontSize,
                                        color = colors.mushafInk,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        else -> {
                            val segments = segmentsFor(line)
                            QpcMushafCanvasLine(
                                segments = segments,
                                typeface = typeface,
                                // QUL page 1/2 rows are all centered. If the bundled
                                // per-page fallback lacks is_centered metadata, keep
                                // the opening spread faithful anyway.
                                centered = openingPage || line.centered,
                                fontSize = lineFontSize,
                                textColor = colors.mushafInk.toArgb(),
                                onSegmentClick = ::clickSegment,
                                modifier = rowModifier
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class MushafGlyphSegment(
    val glyph: String,
    val key: String? = null,
    val translation: String = "",
    val active: Boolean = false,
    val isAyahMarker: Boolean = false
)

private fun splitGlyphUnits(text: String): List<String> {
    if (text.isBlank()) return emptyList()

    val clean = text
        .replace("\u200e", "")
        .replace("\u200f", "")
        .replace("\u061c", "")
        .replace("\ufeff", "")
        .trim()

    if (clean.isBlank()) return emptyList()

    /*
     * Never split a QCF visual word into individual Unicode code points.
     * A single exported QUL word can itself contain multiple glyph codes
     * (for example a word plus its verse-ending ornament).
     */
    val words = clean.split(Regex("\\s+")).filter(String::isNotBlank)
    return if (words.size > 1) words else listOf(clean)
}

@Composable
private fun QpcMushafCanvasLine(
    segments: List<MushafGlyphSegment>,
    typeface: Typeface,
    centered: Boolean,
    fontSize: TextUnit,
    textColor: Int,
    onSegmentClick: (MushafGlyphSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> QpcMushafLineView(ctx) },
        update = { view ->
            view.bind(
                segments = segments,
                typeface = typeface,
                centered = centered,
                targetTextSizePx = sizePx,
                textColor = textColor,
                onSegmentClick = onSegmentClick
            )
        }
    )
}

/**
 * A physical Mushaf line renderer.
 *
 * QUL explicitly marks each line as centered or fully justified. TextView's
 * single-line AutoSize/RTL layout repeatedly clipped the first glyph and changed
 * scale when the player appeared. This view measures the real page-font glyphs,
 * draws them right-to-left on Canvas and never changes one row's font size.
 * QUL line breaks are immutable: this class only positions the exported glyphs.
 */
private class QpcMushafLineView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var segments: List<MushafGlyphSegment> = emptyList()
    private var pageTypeface: Typeface = Typeface.DEFAULT
    private var centered: Boolean = false
    private var targetTextSizePx: Float = 1f
    private var textColor: Int = AndroidColor.BLACK
    private var onSegmentClick: (MushafGlyphSegment) -> Unit = {}
    private val hitRects = ArrayList<Pair<RectF, MushafGlyphSegment>>()
    private val markerRects = ArrayList<RectF>()
    private var renderCache: Bitmap? = null
    private var signature: Int = 0

    init {
        isClickable = true
        isFocusable = false
        layoutDirection = LAYOUT_DIRECTION_RTL
    }

    fun bind(
        segments: List<MushafGlyphSegment>,
        typeface: Typeface,
        centered: Boolean,
        targetTextSizePx: Float,
        textColor: Int,
        onSegmentClick: (MushafGlyphSegment) -> Unit
    ) {
        val nextSignature = 31 * segments.hashCode() +
                17 * System.identityHashCode(typeface) +
                13 * centered.hashCode() +
                targetTextSizePx.toBits() + textColor
        this.onSegmentClick = onSegmentClick
        if (signature == nextSignature) return
        signature = nextSignature
        this.segments = segments
        this.pageTypeface = typeface
        this.centered = centered
        this.targetTextSizePx = targetTextSizePx.coerceAtLeast(1f)
        this.textColor = textColor
        clearCache()
        invalidate()
    }

    private fun clearCache() {
        renderCache?.recycle()
        renderCache = null
        hitRects.clear()
        markerRects.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearCache()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDetachedFromWindow() {
        clearCache()
        super.onDetachedFromWindow()
    }

    private data class LayoutResult(
        val textSize: Float,
        val widths: FloatArray,
        val gap: Float,
        val rightEdge: Float,
        val baseline: Float
    )

    private data class InkMetrics(
        val minX: Float,
        val maxX: Float
    ) {
        val width: Float get() = (maxX - minX).coerceAtLeast(0f)
    }

    private fun calculateLayout(): LayoutResult? {
        if (width <= 0 || height <= 0 || segments.isEmpty()) return null

        paint.typeface = pageTypeface
        paint.textSize = targetTextSizePx
        paint.textScaleX = 1f
        paint.color = textColor
        paint.isAntiAlias = true
        paint.isSubpixelText = true

        /*
         * IMPORTANT: Paint.measureText() returns ADVANCE width, not actual painted
         * bounds. QCF/KFGQPC glyphs very often overhang their advance: a long kaf or
         * lam stroke, dots/harakat and the ayah medallion can extend to either side.
         *
         * The previous implementation justified the sum of advances exactly to the
         * View width. The logical row therefore "fit", but the visible ink could lie
         * outside x=0..width and Android clipped it. That is what the tester screenshot
         * shows: several rows lose the first/last strokes even though maxWidth itself
         * is correct.
         *
         * Keep one common font size. Fit only by inter-word spacing and by positioning
         * the logical row so the REAL ink bounds stay inside the View.
         */
        val widths = FloatArray(segments.size) { i ->
            paint.measureText(segments[i].glyph).coerceAtLeast(0f)
        }

        val glyphBounds = Array(segments.size) { Rect() }
        segments.forEachIndexed { index, segment ->
            val glyph = segment.glyph
            if (glyph.isNotEmpty()) {
                paint.getTextBounds(glyph, 0, glyph.length, glyphBounds[index])
            }
        }

        fun inkMetrics(gapPx: Float): InkMetrics {
            var cursorRight = 0f
            var minInk = Float.POSITIVE_INFINITY
            var maxInk = Float.NEGATIVE_INFINITY

            segments.forEachIndexed { index, _ ->
                val advance = widths[index]
                val drawX = cursorRight - advance
                val b = glyphBounds[index]

                val inkLeft = drawX + b.left.toFloat()
                val inkRight = drawX + b.right.toFloat()
                minInk = min(minInk, inkLeft)
                maxInk = max(maxInk, inkRight)

                cursorRight = drawX
                if (index != segments.lastIndex) cursorRight -= gapPx
            }

            if (!minInk.isFinite() || !maxInk.isFinite()) return InkMetrics(0f, 0f)
            return InkMetrics(minInk, maxInk)
        }

        // Keep real painted ink away from the View edge. This safety is deliberately
        // larger than a typographic space because QCF glyphs and ayah medallions can
        // overhang their logical advance. The page-level scaler uses the same formula.
        val safety = max(paint.textSize * .18f, width.toFloat() * .014f)
        val availableInk = (width.toFloat() - safety * 2f).coerceAtLeast(1f)
        val countGaps = (segments.size - 1).coerceAtLeast(0)
        val zeroGapInk = inkMetrics(0f)

        val gap: Float
        val metrics: InkMetrics

        if (countGaps <= 0) {
            gap = 0f
            metrics = zeroGapInk
        } else {
            // Largest gap that is guaranteed to keep the ACTUAL painted ink inside
            // the row. If rounding on a particular device leaves less room than the
            // preferred gap, fitting wins over appearance -- glyphs are never clipped.
            val maxFitGap = ((availableInk - zeroGapInk.width) / countGaps).coerceAtLeast(0f)
            gap = if (centered) {
                val naturalGap = paint.textSize * .10f
                naturalGap.coerceAtMost(maxFitGap)
            } else {
                /*
                 * QUL non-centered lines are fully justified. Use all remaining space
                 * between exported word-glyph units instead of stretching the glyphs
                 * themselves. This keeps pages 3..604 rectangular while preserving
                 * the exact QPC shapes (textScaleX always stays 1f).
                 */
                maxFitGap
            }
            metrics = inkMetrics(gap)
        }

        /*
         * `metrics` is measured with a logical cursorRight of 0. Translate that row
         * into the View by choosing rightEdge. For a normal row the rightmost painted
         * pixel lands at width-safety; for a centred row the painted ink (not the
         * logical advances) is centred. This removes both left and right clipping.
         */
        val rightEdge = if (centered || segments.size <= 1) {
            val desired = width / 2f - (metrics.minX + metrics.maxX) / 2f
            val minShift = safety - metrics.minX
            val maxShift = width.toFloat() - safety - metrics.maxX
            if (minShift <= maxShift) desired.coerceIn(minShift, maxShift) else maxShift
        } else {
            width.toFloat() - safety - metrics.maxX
        }

        /*
         * Center vertically by REAL glyph ink as well. FontMetrics describes the
         * entire font, not necessarily the current QCF glyphs, so using it alone can
         * place dots/harakat outside a short row on some Android rasterizers.
         */
        var minTop = Float.POSITIVE_INFINITY
        var maxBottom = Float.NEGATIVE_INFINITY
        glyphBounds.forEach { b ->
            if (!b.isEmpty) {
                minTop = min(minTop, b.top.toFloat())
                maxBottom = max(maxBottom, b.bottom.toFloat())
            }
        }

        val baseline = if (minTop.isFinite() && maxBottom.isFinite()) {
            val desired = height / 2f - (minTop + maxBottom) / 2f
            val verticalSafety = max(1f, min(height.toFloat() * .06f, paint.textSize * .12f))
            val minBaseline = verticalSafety - minTop
            val maxBaseline = height.toFloat() - verticalSafety - maxBottom
            if (minBaseline <= maxBaseline) desired.coerceIn(minBaseline, maxBaseline) else desired
        } else {
            val fm = paint.fontMetrics
            height / 2f - (fm.ascent + fm.descent) / 2f
        }

        return LayoutResult(
            textSize = targetTextSizePx,
            widths = widths,
            gap = gap,
            rightEdge = rightEdge,
            baseline = baseline
        )
    }

    private fun drawGlyphs(canvas: Canvas, collectHits: Boolean) {
        val layout = calculateLayout() ?: return
        paint.textSize = layout.textSize
        paint.textScaleX = 1f
        paint.typeface = pageTypeface
        paint.color = textColor
        if (collectHits) {
            hitRects.clear()
            markerRects.clear()
        }

        var cursorRight = layout.rightEdge
        segments.forEachIndexed { index, segment ->
            val w = layout.widths[index]
            val left = cursorRight - w
            canvas.drawText(segment.glyph, left, layout.baseline, paint)
            if (segment.active) {
                val underlineY = min(height - 1f, layout.baseline + paint.textSize * .10f)
                val oldStyle = paint.style
                val oldStroke = paint.strokeWidth
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, paint.textSize * .025f)
                canvas.drawLine(left, underlineY, cursorRight, underlineY, paint)
                paint.style = oldStyle
                paint.strokeWidth = oldStroke
            }
            if (collectHits && segment.key != null) {
                val rect = RectF(left - layout.gap * .35f, 0f, cursorRight + layout.gap * .35f, height.toFloat())
                hitRects += rect to segment
                if (segment.isAyahMarker) markerRects += RectF(left, 0f, cursorRight, height.toFloat())
            }
            cursorRight = left - layout.gap
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (segments.isEmpty()) return
        val avg = (AndroidColor.red(textColor) + AndroidColor.green(textColor) + AndroidColor.blue(textColor)) / 3
        val replaceBlack = avg >= 150
        if (!replaceBlack) {
            drawGlyphs(canvas, collectHits = true)
            return
        }

        var bitmap = renderCache
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            renderCache?.recycle()
            bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val offscreen = Canvas(bitmap)
            drawGlyphs(offscreen, collectHits = true)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rr = AndroidColor.red(textColor)
            val rg = AndroidColor.green(textColor)
            val rb = AndroidColor.blue(textColor)
            for (i in pixels.indices) {
                val c = pixels[i]
                val a = AndroidColor.alpha(c)
                if (a == 0) continue
                val x = i % bitmap.width
                // Keep the complete ayah medallion in its native light ornament +
                // dark digit palette. Recolouring its embedded black digit to white
                // made the number unreadable because the plaque itself stays light.
                val insideAyahMarker = markerRects.any { rect -> x >= rect.left && x <= rect.right }
                if (insideAyahMarker) continue

                val r = AndroidColor.red(c)
                val g = AndroidColor.green(c)
                val b = AndroidColor.blue(c)
                // Outside the marker, neutral QPC ink follows the current Mushaf
                // theme while saturated Tajweed colours remain untouched.
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                if (hi <= 178 && hi - lo <= 28) {
                    pixels[i] = AndroidColor.argb(a, rr, rg, rb)
                }
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            renderCache = bitmap
        }
        canvas.drawBitmap(requireNotNull(bitmap), 0f, 0f, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = hitRects.lastOrNull { (rect, _) -> rect.contains(event.x, event.y) }
            if (hit != null) {
                onSegmentClick(hit.second)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}


private class QpcGlyphTextView(context: Context) : TextView(context) {
    /**
     * QPC V4 embeds a black base layer inside the colour font. Android therefore
     * ignores setTextColor() for that layer. In dark themes we recolour only
     * near-black pixels after TextView has rendered, preserving blue/green/red
     * Tajweed layers exactly as they are. The processed bitmap is cached until
     * the text/layout changes, so normal scrolling does not redo the pixel pass.
     */
    var darkInkReplacement: Int? = null
        set(value) {
            if (field != value) {
                field = value
                clearRenderedCache()
                super.invalidate()
            }
        }

    private var renderedCache: Bitmap? = null
    private var renderSignature: Int = 0

    private fun clearRenderedCache() {
        renderedCache?.recycle()
        renderedCache = null
    }

    fun updateRenderSignature(value: Int) {
        if (renderSignature != value) {
            renderSignature = value
            clearRenderedCache()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearRenderedCache()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDetachedFromWindow() {
        clearRenderedCache()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val replacement = darkInkReplacement
        if (replacement == null || width <= 0 || height <= 0) {
            super.onDraw(canvas)
            return
        }

        var bitmap = renderedCache
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            clearRenderedCache()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val offscreen = Canvas(bitmap)
            super.onDraw(offscreen)

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rr = AndroidColor.red(replacement)
            val rg = AndroidColor.green(replacement)
            val rb = AndroidColor.blue(replacement)
            for (i in pixels.indices) {
                val c = pixels[i]
                val a = AndroidColor.alpha(c)
                if (a == 0) continue
                val r = AndroidColor.red(c)
                val g = AndroidColor.green(c)
                val b = AndroidColor.blue(c)
                // Only the embedded black/near-black ink is replaced. Saturated
                // Tajweed colours and the coloured ayah ornaments stay untouched.
                // QPC ayah medallions keep the verse digit in a neutral dark
                // embedded layer. On a dark Mushaf background that digit used to
                // disappear even though the surrounding ornament remained visible.
                // Recolour neutral dark/medium-gray ink, but leave saturated
                // Tajweed colours untouched.
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                if (hi <= 178 && hi - lo <= 28) {
                    pixels[i] = AndroidColor.argb(a, rr, rg, rb)
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            renderedCache = bitmap
        }
        canvas.drawBitmap(requireNotNull(bitmap), 0f, 0f, null)
    }
}

@Composable
private fun QpcTextView(
    text: CharSequence,
    fontSize: TextUnit,
    textColor: Int,
    lineMultiplier: Float,
    singleLine: Boolean,
    modifier: Modifier,
    autoFit: Boolean = false,
    clickable: Boolean = false,
    fixedHeight: Dp? = null,
    safeBounds: Boolean = false
) {
    val pxSize = fontSize.value
    AndroidView(
        modifier = if (fixedHeight != null) modifier.height(fixedHeight) else modifier,
        factory = { context ->
            QpcGlyphTextView(context).apply {
                textDirection = View.TEXT_DIRECTION_RTL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = if (singleLine) Gravity.CENTER else Gravity.END
                // QCF/QPC glyphs deliberately extend beyond their logical advance
                // (especially dots, madd signs and coloured tajweed layers). Android
                // clips those overhangs when font padding is disabled. Keep font
                // padding and add proportional optical gutters instead of fixed dp.
                includeFontPadding = true
                paint.isSubpixelText = true
                paint.isAntiAlias = true
                clipToOutline = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                if (clickable) {
                    movementMethod = LinkMovementMethod.getInstance()
                    highlightColor = android.graphics.Color.TRANSPARENT
                }
            }
        },
        update = { view ->
            view.text = text
            view.setTextColor(textColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, pxSize)
            val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, pxSize, view.resources.displayMetrics)
            // QPC glyph bounds are intentionally wider than their advance width.
            // A generous proportional gutter plus non-scrolling single-line layout
            // prevents the first/right-most Arabic letter and its harakat from
            // being clipped on phones with different font rasterizers.
            val horizontalGutterFactor = when {
                fixedHeight == null -> .52f
                safeBounds -> .22f
                else -> .16f
            }
            val verticalGutterFactor = when {
                fixedHeight == null -> .16f
                safeBounds -> .08f
                else -> .06f
            }
            val horizontalGutter = (fontPx * horizontalGutterFactor).roundToInt().coerceAtLeast(2)
            val verticalGutter = (fontPx * verticalGutterFactor).roundToInt().coerceAtLeast(1)
            view.includeFontPadding = true
            view.setPadding(horizontalGutter, verticalGutter, horizontalGutter, verticalGutter)
            view.setLineSpacing(0f, lineMultiplier.coerceAtLeast(1f))
            view.maxLines = if (singleLine) 1 else Int.MAX_VALUE
            view.setHorizontallyScrolling(false)
            view.isSingleLine = false

            val avg = (AndroidColor.red(textColor) + AndroidColor.green(textColor) + AndroidColor.blue(textColor)) / 3
            view.darkInkReplacement = if (avg >= 150) textColor else null
            view.updateRenderSignature(
                31 * text.toString().hashCode() + 17 * textColor + fontPx.toBits() + horizontalGutter + verticalGutter
            )
            if (autoFit && singleLine) {
                // For pages 3..604 the larger target size must never turn a long
                // QPC row into clipped/hidden text. Give AutoSize enough range to
                // shrink that one row while keeping ordinary rows visibly larger.
                // Opening pages retain the previous threshold unchanged.
                val minFactor = if (safeBounds) .30f else .46f
                val min = (pxSize * minFactor).roundToInt().coerceAtLeast(1)
                val max = pxSize.roundToInt().coerceAtLeast(min + 1)
                view.setAutoSizeTextTypeUniformWithConfiguration(min, max, 1, TypedValue.COMPLEX_UNIT_SP)
            } else {
                view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            }
        }
    )
}