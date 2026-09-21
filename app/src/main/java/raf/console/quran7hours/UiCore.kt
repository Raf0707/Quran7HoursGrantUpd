package raf.console.quran7hours

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.min

@Immutable
data class AdaptiveMetrics(
    val unit: Dp,
    val textUnit: TextUnit,
    val isWide: Boolean,
    val isLandscape: Boolean
) {
    val xs get() = unit * 1.2f
    val sm get() = unit * 2.0f
    val md get() = unit * 3.2f
    val lg get() = unit * 5.0f
    val xl get() = unit * 7.5f
    val icon get() = unit * 5.2f
    val iconSmall get() = unit * 4.2f
    val touch get() = unit * 10.5f
    val corner get() = unit * 4.2f
    val pagePadding get() = PaddingValues(horizontal = unit * 4.0f, vertical = unit * 3.2f)
    val readerPadding get() = PaddingValues(horizontal = unit * 2.0f, vertical = unit * 2.2f)
    val mushafPadding get() = PaddingValues(horizontal = unit * .75f, vertical = unit * 1.5f)
    val body get() = textUnit * 3.8f
    val bodySmall get() = textUnit * 3.2f
    val title get() = textUnit * 7.2f
    val heading get() = textUnit * 5.4f
    val arabicBase get() = textUnit * 8.6f
}

@Composable
fun AdaptiveContent(content: @Composable (AdaptiveMetrics) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minSide = min(maxWidth.value, maxHeight.value).coerceAtLeast(1f)
        val unit = (minSide / 100f).dp
        val text = (minSide / 100f).coerceIn(2.4f, 7.2f).sp
        val landscape = maxWidth > maxHeight
        val wide = maxWidth > maxHeight * 0.86f
        content(AdaptiveMetrics(unit, text, wide, landscape))
    }
}

val QuranFont = FontFamily(Typeface.create("Noto Naskh Arabic", Typeface.NORMAL))

private val bundledArabicFonts = mapOf(
    "qpc-v4" to "quran/fonts/UthmanicHafs_V22.ttf",
    "qpc-hafs" to "quran/fonts/UthmanicHafs_V22.ttf",
    "me-quran" to "quran/fonts/me_quran_volt_newmet.ttf",
    "digital-v1" to "quran/fonts/DigitalKhattQuranicV1.otf",
    "digital-v2" to "quran/fonts/DigitalKhattV2.otf",
    "indopak" to "quran/fonts/IndopakHanafi.ttf"
)

private fun systemArabicFont(id: String): FontFamily {
    val name = when (id) {
        "scheherazade" -> "Scheherazade New"
        "amiri" -> "Amiri Quran"
        "noto" -> "Noto Naskh Arabic"
        "noorehira" -> "Noorehira"
        "pdms" -> "PDMS Saleem QuranFont"
        "hamdullah" -> "Shaikh Hamdullah"
        "traditional" -> "Traditional Arabic"
        else -> "Noto Naskh Arabic"
    }
    return FontFamily(Typeface.create(name, Typeface.NORMAL))
}

/**
 * Quran fonts are part of the application's offline asset pack. Nothing is
 * downloaded when the reader is opened, so switching fonts cannot block the UI.
 */
@Composable
fun rememberArabicFont(fontId: String): FontFamily {
    val assets = LocalContext.current.applicationContext.assets
    return remember(fontId) {
        bundledArabicFonts[fontId]
            ?.let { path -> runCatching { FontFamily(Typeface.createFromAsset(assets, path)) }.getOrNull() }
            ?: systemArabicFont(fontId)
    }
}

/** Exact semantic colors from the final web build (styles.css). */
@Immutable
data class QuranSemanticColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val warm: Color,
    val danger: Color,
    val mushafPaper: Color,
    val mushafInk: Color,
    val mushafLine: Color,
    val mushafLineStrong: Color,
    val mushafMuted: Color,
    val mushafHeader: Color
)

val LocalQuranColors = staticCompositionLocalOf {
    QuranSemanticColors(
        bg = Color(0xFFF4EFE4), surface = Color(0xFFFCFAF5), surface2 = Color(0xFFEEE6D7),
        text = Color(0xFF21302C), muted = Color(0xFF78817D), line = Color(0xFFDDD4C4),
        accent = Color(0xFF22675B), accentSoft = Color(0xFFDCE9E3), accentText = Color.White,
        warm = Color(0xFFB77D4E), danger = Color(0xFFA64D47),
        mushafPaper = Color(0xFFFBF6E8), mushafInk = Color(0xFF1B241F), mushafLine = Color(0xFFD8CCB0),
        mushafLineStrong = Color(0xFFC9B98F), mushafMuted = Color(0xFF6B604C), mushafHeader = Color(0xFFEEE2CE)
    )
}

private data class WebPalette(
    val bg: Long, val surface: Long, val surface2: Long, val text: Long = 0xFF21302C,
    val muted: Long = 0xFF78817D, val line: Long = 0xFFDDD4C4, val accent: Long,
    val accentSoft: Long, val accentText: Long = 0xFFFFFFFF, val warm: Long,
    val danger: Long = 0xFFA64D47
)

private val webPalettes = mapOf(
    "sand" to WebPalette(0xFFF4EFE4,0xFFFCFAF5,0xFFEEE6D7,accent=0xFF22675B,accentSoft=0xFFDCE9E3,warm=0xFFB77D4E),
    "brown" to WebPalette(0xFFEFE7DB,0xFFFBF5ED,0xFFE3D6C5,accent=0xFF7A513B,accentSoft=0xFFEADBD0,warm=0xFF9A623D),
    "olive" to WebPalette(0xFFEEF0E4,0xFFFBFCF6,0xFFE0E5D2,accent=0xFF5C6842,accentSoft=0xFFE1E6D5,warm=0xFF947247),
    "emerald" to WebPalette(0xFFEAF2EE,0xFFF8FCFA,0xFFDBE9E3,accent=0xFF176653,accentSoft=0xFFD5E9E1,warm=0xFFB27B48),
    "teal" to WebPalette(0xFFE8F1F0,0xFFF8FCFB,0xFFD6E5E4,accent=0xFF236D72,accentSoft=0xFFD6E9E8,warm=0xFFA4774B),
    "blue" to WebPalette(0xFFE9EDF4,0xFFF9FBFE,0xFFDCE4EF,accent=0xFF415F86,accentSoft=0xFFDBE5F1,warm=0xFFA17A56),
    "violet" to WebPalette(0xFFEEEAF3,0xFFFCFAFE,0xFFE3DBEB,accent=0xFF6D5682,accentSoft=0xFFE6DDEF,warm=0xFFA67858),
    "rose" to WebPalette(0xFFF3E9E8,0xFFFFFAFA,0xFFEAD9D7,accent=0xFF8B5A5E,accentSoft=0xFFEFDDDD,warm=0xFFA77854),
    "graphite" to WebPalette(0xFFE9E9E7,0xFFF8F8F6,0xFFDEDEDB,accent=0xFF4E5B58,accentSoft=0xFFDDE3E1,warm=0xFF8F735C),
    "paper" to WebPalette(0xFFF6F3EC,0xFFFFFEFB,0xFFEEEAE1,accent=0xFF365F57,accentSoft=0xFFE2EBE7,warm=0xFFAA7B54),
    "sepia" to WebPalette(0xFFEEE3CF,0xFFFAF2E4,0xFFDFCFB5,accent=0xFF775943,accentSoft=0xFFE8DBC8,warm=0xFF96613D),
    "mint" to WebPalette(0xFFECF4EF,0xFFF9FDFB,0xFFDCEBE2,accent=0xFF4B7461,accentSoft=0xFFDCEBE4,warm=0xFFA37A50)
)

private fun lightSemantic(preset: String): QuranSemanticColors {
    val p = webPalettes[preset] ?: webPalettes.getValue("sand")
    return QuranSemanticColors(
        bg=Color(p.bg), surface=Color(p.surface), surface2=Color(p.surface2), text=Color(p.text), muted=Color(p.muted), line=Color(p.line),
        accent=Color(p.accent), accentSoft=Color(p.accentSoft), accentText=Color(p.accentText), warm=Color(p.warm), danger=Color(p.danger),
        mushafPaper=Color(0xFFFBF6E8), mushafInk=Color(0xFF1B241F), mushafLine=Color(0xFFD8CCB0),
        mushafLineStrong=Color(0xFFC9B98F), mushafMuted=Color(0xFF6B604C), mushafHeader=Color(0xFFEEE2CE)
    )
}

private fun darkSemantic(preset: String): QuranSemanticColors {
    var bg=0xFF121917L; var surface=0xFF17201DL; var surface2=0xFF202B27L; var accent=0xFF77B9A8L; var accentSoft=0xFF233B34L
    when(preset){
        "brown"->{bg=0xFF191411;surface=0xFF211A16;surface2=0xFF2E241E;accent=0xFFC39578;accentSoft=0xFF3A2920}
        "blue"->{bg=0xFF111722;surface=0xFF161F2C;surface2=0xFF202B3B;accent=0xFF91B4E2;accentSoft=0xFF24344A}
        "violet"->{bg=0xFF17131D;surface=0xFF201925;surface2=0xFF2D2234;accent=0xFFC0A0D7;accentSoft=0xFF382A42}
    }
    val brownMushaf = preset=="brown"
    return QuranSemanticColors(
        bg=Color(bg), surface=Color(surface), surface2=Color(surface2), text=Color(0xFFECF1EE), muted=Color(0xFF99A49F), line=Color(0xFF34413C),
        accent=Color(accent), accentSoft=Color(accentSoft), accentText=Color(0xFF0F1B18), warm=Color(0xFFD39A6A), danger=Color(0xFFD98780),
        mushafPaper=Color(if(brownMushaf)0xFF18130F else 0xFF131917), mushafInk=Color(0xFFF4F1EA),
        mushafLine=Color(if(brownMushaf)0xFF4B3A31 else 0xFF3E4A45), mushafLineStrong=Color(if(brownMushaf)0xFF665044 else 0xFF55635D),
        mushafMuted=Color(0xFFB9C1BD), mushafHeader=Color(if(brownMushaf)0xFF291F19 else 0xFF202925)
    )
}

@Composable
fun QuranTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    var semantic = if(dark) darkSemantic(settings.themePreset) else lightSemantic(settings.themePreset)
    semantic = when(settings.contrast){
        ContrastMode.NORMAL -> semantic
        ContrastMode.MEDIUM -> semantic.copy(
            text=forceContrast(semantic.text,semantic.surface,.08f),
            line=mix(semantic.line,semantic.text,.22f)
        )
        ContrastMode.HIGH -> if(dark) semantic.copy(text=Color.White,muted=Color(0xFFD6DBD8),line=Color(0xFF87928D),surface=Color(0xFF0C1110),accent=Color(0xFFA7F1DA))
            else semantic.copy(text=Color(0xFF0A0D0C),muted=Color(0xFF414846),line=Color(0xFF747C78),surface=Color.White,accent=Color(0xFF004C42))
    }

    // Populate every Material role used by default controls. This prevents M3's
    // stock violet/blue secondary colors from leaking into chips, switches,
    // sliders, quotes or buttons when e.g. the "Песок" preset is selected.
    val scheme = if(dark) darkColorScheme(
        primary=semantic.accent, onPrimary=semantic.accentText, primaryContainer=semantic.accentSoft, onPrimaryContainer=semantic.text,
        secondary=semantic.accent, onSecondary=semantic.accentText, secondaryContainer=semantic.accentSoft, onSecondaryContainer=semantic.text,
        tertiary=semantic.warm, onTertiary=semantic.accentText, tertiaryContainer=mix(semantic.warm,semantic.surface2,.70f), onTertiaryContainer=semantic.text,
        error=semantic.danger, onError=Color.White, errorContainer=mix(semantic.danger,semantic.surface2,.72f), onErrorContainer=semantic.text,
        background=semantic.bg, onBackground=semantic.text, surface=semantic.surface, onSurface=semantic.text,
        surfaceVariant=semantic.surface2, onSurfaceVariant=semantic.muted, outline=semantic.line, outlineVariant=mix(semantic.line,semantic.surface,.40f),
        inverseSurface=semantic.text, inverseOnSurface=semantic.surface, inversePrimary=semantic.accent, surfaceTint=semantic.accent, scrim=Color.Black
    ) else lightColorScheme(
        primary=semantic.accent, onPrimary=semantic.accentText, primaryContainer=semantic.accentSoft, onPrimaryContainer=semantic.text,
        secondary=semantic.accent, onSecondary=semantic.accentText, secondaryContainer=semantic.accentSoft, onSecondaryContainer=semantic.text,
        tertiary=semantic.warm, onTertiary=Color.White, tertiaryContainer=mix(semantic.warm,semantic.surface,.78f), onTertiaryContainer=semantic.text,
        error=semantic.danger, onError=Color.White, errorContainer=mix(semantic.danger,semantic.surface,.84f), onErrorContainer=semantic.text,
        background=semantic.bg, onBackground=semantic.text, surface=semantic.surface, onSurface=semantic.text,
        surfaceVariant=semantic.surface2, onSurfaceVariant=semantic.muted, outline=semantic.line, outlineVariant=mix(semantic.line,semantic.surface,.40f),
        inverseSurface=semantic.text, inverseOnSurface=semantic.surface, inversePrimary=semantic.accent, surfaceTint=semantic.accent, scrim=Color.Black
    )

    SystemBarsThemeEffect(dark)
    androidx.compose.runtime.CompositionLocalProvider(LocalQuranColors provides semantic) {
        MaterialTheme(colorScheme=scheme, content=content)
    }
}

@Composable
private fun SystemBarsThemeEffect(dark: Boolean){
    val view=LocalView.current
    if(!view.isInEditMode){
        SideEffect {
            val activity=view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window,view).apply{
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
}

fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)
private fun forceContrast(fg: Color, bg: Color, amount: Float): Color {
    val fgLum = fg.luminance(); val bgLum = bg.luminance()
    return if (fgLum > bgLum) mix(fg, Color.White, amount) else mix(fg, Color.Black, amount)
}

@Composable
fun KeepScreenAwakeEffect(enabled: Boolean) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(enabled, activity) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

fun shareAyah(context: Context, title: String, arabic: String, translation: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$arabic\n\n$translation")
    }
    context.startActivity(Intent.createChooser(intent, title))
}
