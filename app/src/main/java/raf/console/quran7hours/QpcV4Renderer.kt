package raf.quran7hours.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
    val centered: Boolean = false
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
    val content: String
)

private object QulV4LayoutSource {
    private const val ASSET = "quran/qpc-v4/qul/quran_pages.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadStarted = AtomicBoolean(false)
    @Volatile private var loaded: Map<Int, List<QulV4LineSpec>>? = null
    @Volatile private var unavailable = false
    private var job: kotlinx.coroutines.Deferred<Map<Int, List<QulV4LineSpec>>>? = null

    fun start(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        synchronized(this) {
            if (job == null) job = scope.async { load(app) }
        }
    }

    suspend fun page(context: Context, page: Int): List<QulV4LineSpec>? {
        loaded?.let { return it[page] }
        if (unavailable) return null
        start(context)
        val current = synchronized(this) { job } ?: return null
        return runCatching { current.await()[page] }.getOrNull()
    }

    private fun load(context: Context): Map<Int, List<QulV4LineSpec>> {
        val raw = try {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            unavailable = true
            return emptyMap()
        }
        val result = parse(raw)
        if (result.size < 604) {
            unavailable = true
            return emptyMap()
        }
        loaded = result
        return result
    }

    private fun parse(raw: String): Map<Int, List<QulV4LineSpec>> {
        val out = LinkedHashMap<Int, List<QulV4LineSpec>>(604)
        val root = JSONTokener(raw).nextValue()

        fun parsePage(obj: JSONObject, fallbackPage: Int? = null) {
            val pageNumber = obj.optInt("page_number", fallbackPage ?: 0)
            if (pageNumber !in 1..604) return
            val lines = obj.optJSONArray("lines") ?: return
            val parsed = buildList {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val lineNumber = line.optInt("line_number", i + 1)
                    if (lineNumber <= 0) continue
                    add(
                        QulV4LineSpec(
                            lineNumber = lineNumber,
                            lineType = line.optString("line_type", "ayah"),
                            centered = line.optBoolean("is_centered", false),
                            surahNumber = line.optInt("surah_number").takeIf { it > 0 },
                            firstWordId = line.optInt("first_word_id").takeIf { it > 0 },
                            lastWordId = line.optInt("last_word_id").takeIf { it > 0 },
                            content = line.optString("content")
                        )
                    )
                }
            }.sortedBy { it.lineNumber }
            if (parsed.isNotEmpty()) out[pageNumber] = parsed
        }

        when (root) {
            is JSONArray -> {
                for (i in 0 until root.length()) root.optJSONObject(i)?.let { parsePage(it) }
            }
            is JSONObject -> {
                val pages = root.optJSONArray("pages")
                if (pages != null) {
                    for (i in 0 until pages.length()) pages.optJSONObject(i)?.let { parsePage(it) }
                } else {
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = root.optJSONObject(key) ?: continue
                        parsePage(obj, key.toIntOrNull())
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
                        centered = false
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
    private fun applyQulLayout(legacy: QpcV4Page, specs: List<QulV4LineSpec>): QpcV4Page {
        val legacyAyahWords = legacy.lines
            .filterNot { it.type == "surah-header" || it.type == "surah_name" || it.type == "basmala" || it.type == "basmallah" }
            .flatMap { it.words }
        var cursor = 0

        fun consumeWords(content: String): List<QpcV4Word> {
            val target = normalizedGlyphs(content)
            if (target.isBlank() || cursor >= legacyAyahWords.size) return emptyList()
            val maxProbe = min(legacyAyahWords.lastIndex, cursor + 6)
            for (start in cursor..maxProbe) {
                val acc = StringBuilder()
                var end = start
                while (end < legacyAyahWords.size && acc.length <= target.length + 8) {
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

        val mapped = specs.sortedBy { it.lineNumber }.map { spec ->
            val type = when (spec.lineType.lowercase()) {
                "surah_name", "surah-name", "surah_header", "surah-header" -> "surah-header"
                "basmallah", "basmala", "bismillah" -> "basmala"
                else -> "text"
            }
            val words = if (type == "text") consumeWords(spec.content) else emptyList()
            val legacySameLine = legacy.lines.firstOrNull { it.line == spec.lineNumber }
            QpcV4Line(
                line = spec.lineNumber,
                type = type,
                text = legacySameLine?.text.orEmpty(),
                surah = spec.surahNumber ?: legacySameLine?.surah,
                qpcGlyphs = spec.content.ifBlank {
                    if (words.isNotEmpty()) words.joinToString("") { it.glyph } else legacySameLine?.qpcGlyphs.orEmpty()
                },
                words = words,
                // The first two Madani pages are a special opening spread.
                // In QUL/Tarteel they are not justified like ordinary pages:
                // every Quranic line keeps its natural width and is centered,
                // which creates the characteristic rounded/oval silhouette.
                centered = if (legacy.page <= 2 && type != "surah-header") true else spec.centered
            )
        }
        return QpcV4Page(legacy.page, mapped)
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
    val (qpcPage, typeface) = data

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

                val loc = word.location.split(':').mapNotNull(String::toIntOrNull)
                val canonicalSurah = loc.getOrNull(0) ?: 0
                val canonicalAyah = loc.getOrNull(1) ?: 0
                val canonicalPosition = loc.getOrNull(2) ?: 0
                if (page == 1 && canonicalSurah == 1 && canonicalAyah == 7 && canonicalPosition == 4) {
                    fatihaMarkers[6]?.takeIf(String::isNotBlank)?.let { marker ->
                        add(MushafGlyphSegment(marker, "1:6", active = activeKey("1:6"), isAyahMarker = true))
                    }
                }
                if (originalMarker.isNotBlank() && key != null && key != "bismillah") {
                    val appAyah = key.substringAfter(':').toIntOrNull() ?: 0
                    val marker = if (page == 1 && canonicalSurah == 1) {
                        fatihaMarkers[appAyah].orEmpty().ifBlank { originalMarker }
                    } else originalMarker
                    add(MushafGlyphSegment(marker, key, active = activeKey(key), isAyahMarker = true))
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current

        // QUL gives us the physical 15-line Madani layout. Do not derive the page
        // geometry from the phone's *height*: on tall screens that stretches the
        // printed page vertically. The physical line pitch is instead anchored to
        // page width, just like a real Mushaf. 1.74 is the measured 15-line body
        // height/width ratio of the QPC Madani page; maxHeight is only a safety cap
        // for short devices / split-screen.
        val physicalSlots = 15
        val qulGridHeight = minOf(maxHeight, maxWidth * 1.74f)
        val slotHeight = qulGridHeight / physicalSlots.toFloat()

        // QUL/QPC v4 is a 15-line print layout: the rows are tall, but the glyph body
        // occupies only about half of that pitch. Using most of the row height (the old
        // .75 factor) made the exact QUL word groups wider than the page and clipped the
        // first/last words. Keep one *physical* glyph size for all 604 pages, derived
        // only from the 15-line grid. This matches the proportions of the QUL preview
        // much more closely and deliberately does not depend on how many words a page has.
        val lineFontSize = with(density) { (slotHeight * .52f).toSp() }

        val sortedLines = qpcPage.lines.sortedBy { it.line }

        /*
         * Pages 1–2 are the Madani opening spread and intentionally do NOT use the
         * ordinary full-width page geometry. QUL/Tarteel renders those lines as one
         * compact centered block with natural line widths. The empty rows are real
         * page space; we must not enlarge the glyphs to fill them.
         *
         * We keep the same 15-row pitch and the same font size as pages 3–604, but
         * move the complete opening block into the middle/top-middle of that frame.
         * This preserves the familiar "round" opening-page silhouette.
         */
        val isOpeningSpread = page == 1 || page == 2
        val exportedMinLine = sortedLines.minOfOrNull { it.line } ?: 1
        val exportedMaxLine = sortedLines.maxOfOrNull { it.line } ?: 1
        val exportedSpan = (exportedMaxLine - exportedMinLine + 1).coerceAtLeast(1)

        val openingStartSlot = if (isOpeningSpread) {
            // QUL opening pages occupy a compact block rather than all 15 rows.
            // Bias the block slightly upward, matching the printed/Tarteel spread.
            ((physicalSlots - exportedSpan) / 2).coerceAtLeast(0)
        } else 0

        val lineBySlot = sortedLines.associateBy { line ->
            if (isOpeningSpread) {
                (line.line - exportedMinLine + 1 + openingStartSlot).coerceIn(1, physicalSlots)
            } else {
                line.line.coerceIn(1, physicalSlots)
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxWidth().height(qulGridHeight)) {
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
                                    // Keep the title inside one physical Mushaf row. The old
                                    // Russian pill changed the page geometry and made pages 1/2
                                    // unlike a real Mushaf opening.
                                    androidx.compose.material3.Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(m.corner * .18f),
                                        color = androidx.compose.ui.graphics.Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(m.xs * .055f, colors.mushafLineStrong),
                                        modifier = Modifier.fillMaxWidth(.94f)
                                    ) {
                                        Box(
                                            Modifier.fillMaxWidth().padding(horizontal = m.sm, vertical = m.xs * .12f),
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
                            // QUL normally carries surah_number on the basmala line.
                            // Fall back to the next Quranic line so the basmala still
                            // belongs to the surah it visually introduces.
                            val nextQuranLine = sortedLines.firstOrNull { candidate ->
                                candidate.line > line.line && candidate.type != "surah-header" && candidate.type != "basmala"
                            }
                            val basmalaSurah = line.surah
                                ?: nextQuranLine?.surah
                                ?: nextQuranLine?.words?.firstOrNull()?.location?.substringBefore(':')?.toIntOrNull()
                                ?: 1
                            val basmalaKey = "bismillah:$basmalaSurah"
                            Box(rowModifier, contentAlignment = Alignment.Center) {
                                if (ready != null && ready.first.isNotEmpty()) {
                                    val segments = ready.first.map { word ->
                                        val glyph = stripVerseNumberGlyph(word.glyph, word.word)
                                        MushafGlyphSegment(glyph = glyph, key = basmalaKey, active = activeKey(basmalaKey))
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
                                // Pages 1–2 are the special opening spread: never justify
                                // them edge-to-edge. Natural centered widths are what form
                                // the rounded Tarteel/QUL geometry.
                                centered = if (isOpeningSpread) true else line.centered,
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
    val out = ArrayList<String>()
    var index = 0
    while (index < text.length) {
        val cp = Character.codePointAt(text, index)
        val unit = String(Character.toChars(cp))
        if (!unit.all(Char::isWhitespace) && cp != 0x200E && cp != 0x200F && cp != 0x061C && cp != 0xFEFF) {
            out += unit
        }
        index += Character.charCount(cp)
    }
    return out
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
 * draws them right-to-left on Canvas, distributes only the inter-glyph space for
 * justified lines, and shrinks one line only when its measured ink cannot fit.
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

    private fun calculateLayout(): LayoutResult? {
        if (width <= 0 || height <= 0 || segments.isEmpty()) return null
        paint.typeface = pageTypeface
        paint.textSize = targetTextSizePx
        paint.textScaleX = 1f
        paint.color = textColor
        paint.isAntiAlias = true
        paint.isSubpixelText = true

        fun measure(): FloatArray = FloatArray(segments.size) { i ->
            paint.measureText(segments[i].glyph).coerceAtLeast(0f)
        }

        // Preserve the native QPC glyph proportions. QUL owns the line breaks, while
        // is_centered tells us whether the row is centered or justified. Never use
        // textScaleX: if a device/font rasterizer produces a tiny width overshoot, the
        // emergency fit below changes X and Y together, so letters keep their shape.
        val safety = max(3f, width.toFloat() * .014f)
        val available = (width.toFloat() - safety * 2f).coerceAtLeast(1f)
        val countGaps = (segments.size - 1).coerceAtLeast(0)

        var widths = measure()
        var ink = widths.sum()
        var naturalGap = max(paint.measureText(" "), paint.textSize * .010f)
        val naturalTotal = ink + naturalGap * countGaps
        if (naturalTotal > available) {
            // This is only a safety net. With the QUL .52 grid scale it normally stays
            // at 1.0 on all 604 pages. Unlike horizontal squeezing, this is isotropic.
            val fit = (available / naturalTotal).coerceIn(.86f, 1f)
            paint.textSize *= fit
            widths = measure()
            ink = widths.sum()
            naturalGap = max(paint.measureText(" "), paint.textSize * .010f)
        }

        val gap: Float
        val rightEdge: Float
        if (centered || segments.size <= 1) {
            gap = naturalGap
            val total = ink + gap * countGaps
            rightEdge = width / 2f + total / 2f
        } else {
            // QUL is_centered=false => fully justified line. We fill the remaining
            // width with inter-word spacing, exactly as the layout data requests.
            gap = if (segments.size > 1 && ink < available) {
                (available - ink) / countGaps
            } else 0f
            rightEdge = width.toFloat() - safety
        }

        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        return LayoutResult(paint.textSize, widths, gap, rightEdge, baseline)
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
