package raf.console.quran7hours

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

private val tajweedColors = mapOf(
    "ham_wasl" to Color(0xFF9E9E9E),
    "laam_shamsiyah" to Color(0xFF9E9E9E),
    "madda_normal" to Color(0xFF2E73B8),
    "madda_permissible" to Color(0xFF5F61C8),
    "madda_obligatory" to Color(0xFF7A3EC8),
    "madda_necessary" to Color(0xFFA23BB3),
    "ikhafa" to Color(0xFFCE6B24),
    "ikhafa_shafawi" to Color(0xFFCE6B24),
    "idgham_shafawi" to Color(0xFF248B73),
    "iqlab" to Color(0xFF8D5CA8),
    "idgham_ghunnah" to Color(0xFF168C5C),
    "idgham_wo_ghunnah" to Color(0xFF38836D),
    "qalqalah" to Color(0xFFD04E45),
    "ghunnah" to Color(0xFFB24E83)
)

fun tajweedText(html: String, colors: Boolean = true, includeEnd: Boolean = false): AnnotatedString = buildAnnotatedString {
    var i = 0
    var active: String? = null
    var skipEnd = false
    while (i < html.length) {
        if (html[i] == '<') {
            val end = html.indexOf('>', i).takeIf { it >= 0 } ?: break
            val tag = html.substring(i + 1, end)
            when {
                tag.startsWith("tajweed") -> active = Regex("class=([^ >]+)").find(tag)?.groupValues?.getOrNull(1)
                tag.startsWith("/tajweed") -> active = null
                tag.startsWith("span") && tag.contains("class=end") -> skipEnd = !includeEnd
                tag.startsWith("/span") -> skipEnd = false
            }
            i = end + 1
            continue
        }
        if (!skipEnd) {
            val start = length
            append(html[i])
            if (colors && active != null) tajweedColors[active]?.let { addStyle(SpanStyle(color = it), start, length) }
        }
        i++
    }
}

fun tajweedWords(html: String, colors: Boolean = true): List<AnnotatedString> {
    val out = mutableListOf<AnnotatedString>()
    var builder = AnnotatedString.Builder()
    var i = 0
    var active: String? = null
    var skipEnd = false
    fun flush() {
        if (builder.length > 0) out += builder.toAnnotatedString()
        builder = AnnotatedString.Builder()
    }
    while (i < html.length) {
        if (html[i] == '<') {
            val end = html.indexOf('>', i).takeIf { it >= 0 } ?: break
            val tag = html.substring(i + 1, end)
            when {
                tag.startsWith("tajweed") -> active = Regex("class=([^ >]+)").find(tag)?.groupValues?.getOrNull(1)
                tag.startsWith("/tajweed") -> active = null
                tag.startsWith("span") && tag.contains("class=end") -> { skipEnd = true; flush() }
                tag.startsWith("/span") -> skipEnd = false
            }
            i = end + 1
            continue
        }
        val ch = html[i]
        if (!skipEnd) {
            if (ch.isWhitespace()) flush()
            else {
                val start = builder.length
                builder.append(ch)
                if (colors && active != null) tajweedColors[active]?.let { builder.addStyle(SpanStyle(color = it), start, builder.length) }
            }
        }
        i++
    }
    flush()
    return out
}

fun appKeyForCanonicalWord(word: WordItem): Pair<String, Int>? {
    if (word.surah != 1) return "${word.surah}:${word.ayah}" to word.position
    return when {
        word.ayah == 1 -> "bismillah" to word.position
        word.ayah in 2..6 -> "1:${word.ayah - 1}" to word.position
        word.ayah == 7 && word.position <= 4 -> "1:6" to word.position
        word.ayah == 7 -> "1:7" to (word.position - 4)
        else -> null
    }
}

fun wordsForAppAyah(page: WordPage, surah: Int, ayah: Int): List<WordItem> =
    page.words.filter { appKeyForCanonicalWord(it)?.first == "$surah:$ayah" }
