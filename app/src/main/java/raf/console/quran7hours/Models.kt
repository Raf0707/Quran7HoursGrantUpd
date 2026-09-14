package raf.quran7hours.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class QuranMeta(
    val title: String = "Коран за 7 часов",
    val translation: String = "Azan.ru",
    val tafsir: String = "Azan.ru",
    val pages: Int = 604,
    val surahs: List<SurahMeta> = emptyList(),
    val juz: List<JuzMeta> = emptyList(),
    val totalAyahs: Int = 6236
)

@Serializable
data class SurahMeta(
    val id: Int,
    val nameAr: String,
    val nameRu: String,
    val meaning: String,
    val ayahs: Int,
    val revelation: String,
    val pageStart: Int,
    val pageEnd: Int,
    val juz: List<Int> = emptyList()
)

@Serializable
data class JuzMeta(
    val id: Int,
    val start: JuzPoint,
    val end: JuzPoint
)

@Serializable
data class JuzPoint(val surah: Int, val ayah: Int)

@Serializable
data class Bismillah(val ar: String, val tw: String)

@Serializable
data class SurahIntro(
    val translation: String = "",
    val tafsir: String = "",
    val tafsirBlocks: List<JsonObject>? = null,
    val page: Int? = null
)

@Serializable
data class Ayah(
    val a: Int,
    val p: Int,
    val j: Int,
    val ar: String,
    val tw: String,
    val tr: String,
    val tf: String = "",
    val tfBlocks: List<JsonObject>? = null
)

@Serializable
data class SurahData(
    val id: Int,
    val nameAr: String,
    val nameRu: String,
    val meaning: String,
    val revelation: String,
    val bismillah: Bismillah? = null,
    val intro: SurahIntro? = null,
    val ayahs: List<Ayah> = emptyList(),
    val tafsirSchemaVersion: Int? = null
)

data class PageAyah(
    val surah: Int,
    val surahName: String,
    val surahAr: String,
    val ayah: Ayah
) {
    val a get() = ayah.a
    val p get() = ayah.p
    val j get() = ayah.j
}

@Serializable
data class WordPage(
    val page: Int,
    val count: Int = 0,
    val words: List<WordItem> = emptyList()
)

@Serializable
data class WordItem(
    val page: Int,
    val surah: Int,
    val ayah: Int,
    val position: Int,
    val location: String,
    val line: Int,
    val arabic: String,
    val kind: String = "word",
    @SerialName("index_on_page") val indexOnPage: Int = 0,
    val translation: String = ""
)

data class MushafLineGeometry(val width: Float, val align: Char)

data class AlautdinovEntry(val translation: String = "", val tafsir: String = "")

data class BookmarkItem(val key: String, val color: Long, val createdAt: Long)
data class RecentItem(val key: String, val at: Long)

enum class ReadingMode { SURAH, PAGE, MUSHAF }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ContrastMode { NORMAL, MEDIUM, HIGH }
enum class MushafSpacingMode { FIXED, ACADEMY }
enum class MushafHoverMode { BOTH, WORD, AYAH, OFF }
enum class ContentBlock { AZAN_TRANSLATION, ALAUTDINOV_TRANSLATION, AZAN_TAFSIR, ALAUTDINOV_TAFSIR }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val contrast: ContrastMode = ContrastMode.NORMAL,
    val themePreset: String = "sand",
    val keepAwake: Boolean = false,
    val showArabic: Boolean = true,
    val showTranslation: Boolean = true,
    val showTafsir: Boolean = true,
    val showFootnotes: Boolean = true,
    val showWordByWord: Boolean = true,
    val tajweed: Boolean = true,
    val arabicScale: Float = 1.0f,
    val translationScale: Float = 1.0f,
    val wordByWordScale: Float = .40f,
    val tafsirScale: Float = 1.0f,
    val footnoteScale: Float = 1.0f,
    val lineHeight: Float = 2.05f,
    val letterSpacing: Float = 0f,
    val mushafWordSpacing: Float = .04f,
    val mushafSpacingMode: MushafSpacingMode = MushafSpacingMode.ACADEMY,
    val mushafHoverMode: MushafHoverMode = MushafHoverMode.BOTH,
    val arabicFont: String = "qpc-v4",
    val reciter: String = "alafasy",
    val autoAdvance: Boolean = true,
    val autoPageTurnAudio: Boolean = false,

    // Persist the selected reader mode and reader-sheet visibility options.
    // These fields were accidentally dropped in v12, which caused
    // settings.readingMode / copy(readingMode = ...) to become unresolved.
    val readingMode: ReadingMode = ReadingMode.MUSHAF,
    val readerShowAudio: Boolean = true,
    val readerShowArabic: Boolean = false,
    val readerShowTafsir: Boolean = true,

    val contentOrder: List<ContentBlock> = listOf(
        ContentBlock.AZAN_TRANSLATION,
        ContentBlock.ALAUTDINOV_TRANSLATION,
        ContentBlock.AZAN_TAFSIR,
        ContentBlock.ALAUTDINOV_TAFSIR
    )
)

sealed interface AppRoute {
    data object Home : AppRoute
    data object Audio : AppRoute
    data object Bookmarks : AppRoute
    data object Settings : AppRoute
    data class AyahRoute(val surah: Int, val ayah: Int) : AppRoute
    data class PageRoute(val page: Int, val mode: ReadingMode) : AppRoute
}

fun readingRoute(
    mode: ReadingMode,
    surah: Int,
    ayah: Int,
    page: Int
): AppRoute = when (mode) {
    ReadingMode.SURAH -> AppRoute.AyahRoute(
        surah = surah.coerceIn(1, 114),
        ayah = ayah.coerceAtLeast(1)
    )
    ReadingMode.PAGE -> AppRoute.PageRoute(
        page = page.coerceIn(1, 604),
        mode = ReadingMode.PAGE
    )
    ReadingMode.MUSHAF -> AppRoute.PageRoute(
        page = page.coerceIn(1, 604),
        mode = ReadingMode.MUSHAF
    )
}

data class ReaderCoordinate(
    val surah: Int = 1,
    val ayah: Int = 1,
    val page: Int = 1,
    val juz: Int = 1,
    val range: String = "1–7"
)

data class Track(
    val surah: Int,
    val ayah: Int,
    val endAyah: Int = ayah,
    val name: String,
    val kind: TrackKind = TrackKind.AYAH
)

enum class TrackKind { AYAH, BISMILLAH }

data class PlaybackSpec(
    val candidates: List<String>,
    val providerAyah: Int,
    val groupStartAyah: Int,
    val groupEndAyah: Int
) {
    val uri: String get() = candidates.first()
}

/**
 * EveryAyah catalog used by the offline-audio installer.
 *
 * The EveryAyah directory contains several mirrors/bitrates of the same voice.
 * In the app we expose one complete Hafs verse-by-verse set per distinct reciter
 * (plus genuinely different recitation styles such as Murattal/Mujawwad/Muallim).
 * Translation tracks, Warsh tracks and directories explicitly marked by
 * EveryAyah as incomplete are intentionally excluded because they cannot satisfy
 * the "download the whole reciter, then play fully offline" contract.
 *
 * Where EveryAyah offers several complete bitrates for the same recording we
 * prefer the smaller complete set, normally 64 kbps, to reduce the one-time
 * download and device storage cost.
 */
val RECITERS = linkedMapOf(
    // Existing IDs are preserved so old user preferences keep working.
    "alafasy" to Reciter("Мишари Рашид Аль-Афаси", "Alafasy_64kbps", 64),
    "abdulbasit" to Reciter("Абдулбасит Абдуссамад · Муратталь", "Abdul_Basit_Murattal_64kbps", 64),
    "husary" to Reciter("Махмуд Халиль Аль-Хусари · Муратталь", "Husary_64kbps", 64),
    "minshawi" to Reciter("Мухаммад Сиддик аль-Миншави · Муратталь", "Minshawy_Murattal_128kbps", 128),
    "shatri" to Reciter("Абу Бакр Аш-Шатри", "Abu_Bakr_Ash-Shaatree_64kbps", 64),

    "abdulbasit_mujawwad" to Reciter("Абдулбасит Абдуссамад · Муджаввад", "Abdul_Basit_Mujawwad_128kbps", 128),
    "juhany" to Reciter("Абдуллах Авад аль-Джухани", "Abdullaah_3awwaad_Al-Juhaynee_128kbps", 128),
    "basfar" to Reciter("Абдуллах Басфар", "Abdullah_Basfar_64kbps", 64),
    "matroud" to Reciter("Абдуллах Матруд", "Abdullah_Matroud_128kbps", 128),
    "sudais" to Reciter("Абдуррахман ас-Судайс", "Abdurrahmaan_As-Sudais_64kbps", 64),
    "ajamy" to Reciter("Ахмад ибн Али аль-Аджами", "Ahmed_ibn_Ali_al-Ajamy_64kbps_QuranExplorer.Com", 64),
    "neana" to Reciter("Ахмад Ниана", "Ahmed_Neana_128kbps", 128),
    "alaqimy" to Reciter("Акрам аль-Алякими", "Akram_AlAlaqimy_128kbps", 128),
    "ali_jaber" to Reciter("Али Джабир", "Ali_Jaber_64kbps", 64),
    "ali_hajjaj" to Reciter("Али Хаджжадж ас-Сувейси", "Ali_Hajjaj_AlSuesy_128kbps", 128),
    "ayman_sowaid" to Reciter("Айман Сувайд", "Ayman_Sowaid_64kbps", 64),
    "aziz_alili" to Reciter("Азиз Алили", "aziz_alili_128kbps", 128),
    "fares_abbad" to Reciter("Фарис Аббад", "Fares_Abbad_64kbps", 64),
    "ghamdi" to Reciter("Саад аль-Гамиди", "Ghamadi_40kbps", 40),
    "hani_rifai" to Reciter("Хани ар-Рифаи", "Hani_Rifai_64kbps", 64),
    "hudhaify" to Reciter("Али аль-Хузайфи", "Hudhaify_64kbps", 64),

    "husary_muallim" to Reciter("Махмуд Халиль Аль-Хусари · Муаллим", "Husary_Muallim_128kbps", 128),
    "husary_mujawwad" to Reciter("Махмуд Халиль Аль-Хусари · Муджаввад", "Husary_Mujawwad_64kbps", 64),
    "ibrahim_akhdar" to Reciter("Ибрахим аль-Ахдар", "Ibrahim_Akhdar_32kbps", 32),
    "karim_mansoori" to Reciter("Карим Мансури", "Karim_Mansoori_40kbps", 40),
    "khalefa_tunaiji" to Reciter("Халифа ат-Тунайджи", "khalefa_al_tunaiji_64kbps", 64),
    "khalid_qahtani" to Reciter("Халид Абдуллах аль-Кахтани", "Khaalid_Abdullaah_al-Qahtaanee_192kbps", 192),
    "maher_muaiqly" to Reciter("Махер аль-Муайкли", "Maher_AlMuaiqly_64kbps", 64),
    "mahmoud_banna" to Reciter("Махмуд Али аль-Банна", "mahmoud_ali_al_banna_32kbps", 32),
    "minshawi_mujawwad" to Reciter("Мухаммад Сиддик аль-Миншави · Муджаввад", "Minshawy_Mujawwad_64kbps", 64),
    "tablaway" to Reciter("Мухаммад ат-Таблауи", "Mohammad_al_Tablaway_64kbps", 64),
    "abdulkareem" to Reciter("Мухаммад Абдулькарим", "Muhammad_AbdulKareem_128kbps", 128),
    "ayyoub" to Reciter("Мухаммад Айюб", "Muhammad_Ayyoub_64kbps", 64),
    "jibreel" to Reciter("Мухаммад Джибриль", "Muhammad_Jibreel_64kbps", 64),
    "muhsin_qasim" to Reciter("Мухсин аль-Касим", "Muhsin_Al_Qasim_192kbps", 192),
    "nasser_qatami" to Reciter("Насер аль-Катами", "Nasser_Alqatami_128kbps", 128),
    "parhizgar" to Reciter("Пархизгар", "Parhizgar_48kbps", 48),
    "sahl_yassin" to Reciter("Сахль Ясин", "Sahl_Yassin_128kbps", 128),
    "bukhatir" to Reciter("Салах Бу Хатир", "Salaah_AbdulRahman_Bukhatir_128kbps", 128),
    "budair" to Reciter("Салах аль-Будаир", "Salah_Al_Budair_128kbps", 128),
    "shuraym" to Reciter("Сауд аш-Шурайм", "Saood_ash-Shuraym_64kbps", 64),
    "yaser_salamah" to Reciter("Ясир Салама", "Yaser_Salamah_128kbps", 128),
    "dussary" to Reciter("Ясир ад-Дусари", "Yasser_Ad-Dussary_128kbps", 128)
)

data class Reciter(
    val name: String,
    val everyAyahDir: String,
    val bitrateKbps: Int,
    // Kept for source compatibility with older builds that had online fallbacks.
    val tarteelSlug: String = "",
    val foundationDir: String = ""
)

fun githubAyahAudioUrl(reciterId: String, surah: Int, providerAyah: Int): String {
    val safeReciter = if (reciterId == "nabil_rifai" || reciterId !in RECITERS) "alafasy" else reciterId
    val s = surah.toString().padStart(3, '0')
    val a = providerAyah.toString().padStart(3, '0')
    return "https://raw.githubusercontent.com/Raf0707/q7h_${safeReciter}/main/$s/$s$a.mp3"
}

fun githubBismillahAudioUrl(reciterId: String, surah: Int): String {
    val safeReciter = if (reciterId == "nabil_rifai" || reciterId !in RECITERS) "alafasy" else reciterId
    val s = surah.toString().padStart(3, '0')
    return "https://raw.githubusercontent.com/Raf0707/q7h_${safeReciter}/main/$s/basmala.mp3"
}

fun everyAyahAudioUrl(reciterId: String, surah: Int, providerAyah: Int): String {
    val reciter = RECITERS[reciterId] ?: RECITERS.getValue("alafasy")
    val code = surah.toString().padStart(3, '0') +
            providerAyah.toString().padStart(3, '0') + ".mp3"
    return "https://everyayah.com/data/${reciter.everyAyahDir}/$code"
}

/**
 * Legacy candidate list. New ayah playback is local-only after a full reciter
 * pack is installed; the downloader itself deliberately uses EveryAyah directly.
 */
fun reciterAudioCandidates(reciterId: String, surah: Int, providerAyah: Int): List<String> {
    val safeReciter = if (reciterId == "nabil_rifai" || reciterId !in RECITERS) "alafasy" else reciterId
    return listOf(
        githubAyahAudioUrl(safeReciter, surah, providerAyah),
        everyAyahAudioUrl(safeReciter, surah, providerAyah)
    ).distinct()
}

fun ayahPlayback(reciterId: String, surah: Int, ayah: Int): PlaybackSpec {
    val providerAyah = when {
        surah != 1 -> ayah
        ayah in 1..5 -> ayah + 1
        else -> 7
    }
    val start = if (surah == 1 && ayah >= 6) 6 else ayah
    val end = if (surah == 1 && ayah >= 6) 7 else ayah
    return PlaybackSpec(reciterAudioCandidates(reciterId, surah, providerAyah), providerAyah, start, end)
}

fun bismillahPlayback(reciterId: String, surah: Int = 1): PlaybackSpec {
    val providerAyah = if (surah == 1) 1 else 0
    return PlaybackSpec(reciterAudioCandidates(reciterId, surah, providerAyah), providerAyah, 0, 0)
}

fun pluralizeAyah(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "аят"
        m10 in 2..4 && m100 !in 12..14 -> "аята"
        else -> "аятов"
    }
}
