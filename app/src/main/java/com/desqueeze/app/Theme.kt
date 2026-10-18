package com.desqueeze.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Projector amber: the brand accent and default. */
val Flare = Color(0xFFF2A33A)

/**
 * Accent colours over warm, near-black "screening room" neutrals (no colour cast that competes with the footage).
 * Every accent shade is derived from one colour.
 */
enum class Accent(val label: String, val argb: Long) {
    AMBER("Amber", 0xFFF2A33A), FLARE("Flare blue", 0xFF5AA9FF), CYAN("Cyan", 0xFF3DD6E0), MINT("Mint", 0xFF4FD8A4),
    VIOLET("Violet", 0xFFA98BFF), ROSE("Rose", 0xFFFF7FA3), MONO("Mono", 0xFFE4E6EA),
    DYNAMIC("Wallpaper", 0),
}

private fun onColor(c: Color) = if (c.luminance() > 0.40f) Color(0xFF120C02) else Color.White

fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent, onPrimary = onColor(accent),
    primaryContainer = lerp(Color(0xFF14120F), accent, 0.22f), onPrimaryContainer = lerp(accent, Color.White, 0.55f),
    secondary = lerp(accent, Color(0xFFA39A8C), 0.5f), onSecondary = Color(0xFF120C02),
    secondaryContainer = lerp(Color(0xFF14120F), accent, 0.22f), onSecondaryContainer = lerp(accent, Color.White, 0.55f),
    tertiary = Color(0xFFEEE9E1), onTertiary = Color(0xFF120C02),
    background = Color(0xFF080807), onBackground = Color(0xFFEEE9E1),
    surface = Color(0xFF080807), onSurface = Color(0xFFEEE9E1),
    surfaceVariant = Color(0xFF1F1B16), onSurfaceVariant = Color(0xFFA39A8C),
    surfaceContainerLowest = Color(0xFF050504), surfaceContainerLow = Color(0xFF100E0C),
    surfaceContainer = Color(0xFF15130F), surfaceContainerHigh = Color(0xFF1C1915), surfaceContainerHighest = Color(0xFF25211B),
    surfaceTint = accent,
    outline = Color(0xFF3A332A), outlineVariant = Color(0xFF2B2620),
    inverseSurface = Color(0xFFEEE9E1), inverseOnSurface = Color(0xFF15130F),
    error = Color(0xFFFF8A73), onError = Color(0xFF1A0705), errorContainer = Color(0xFF3A1A14), onErrorContainer = Color(0xFFFFD9D0),
)

fun lightScheme(accent: Color): ColorScheme {
    // Darken the accent just enough for text and icons on paper to reach WCAG AA (4.5:1).
    fun contrastOnWhite(c: Color) = 1.05f / (c.luminance() + 0.05f)
    var p = accent; var i = 0
    while (contrastOnWhite(p) < 4.6f && i++ < 30) p = lerp(p, Color.Black, 0.06f)
    return lightColorScheme(
        primary = p, onPrimary = onColor(p),
        primaryContainer = lerp(Color.White, accent, 0.22f), onPrimaryContainer = lerp(p, Color.Black, 0.45f),
        secondaryContainer = lerp(Color.White, accent, 0.22f), onSecondaryContainer = lerp(p, Color.Black, 0.45f),
        background = Color(0xFFF4F0E8), onBackground = Color(0xFF1A160F),
        surface = Color(0xFFF4F0E8), onSurface = Color(0xFF1A160F),
        surfaceVariant = Color(0xFFE9E3D7), onSurfaceVariant = Color(0xFF6B6255),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBF8F2),
        surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEFE9DE), surfaceContainerHighest = Color(0xFFE7E0D2),
        outline = Color(0xFFCFC6B6), outlineVariant = Color(0xFFE2DACB),
    )
}

/** Space Grotesk for everything (geometric, a little technical), Space Mono for numbers. */
val Grotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal), Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold), Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
val MonoFamily = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal))

val AppType = Typography().let { t ->
    fun TextStyle.g(w: FontWeight? = null, ls: Float? = null) = copy(fontFamily = Grotesk, fontWeight = w ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
    Typography(
        displayLarge = t.displayLarge.g(FontWeight.Bold), displayMedium = t.displayMedium.g(FontWeight.Bold), displaySmall = t.displaySmall.g(FontWeight.Bold),
        headlineLarge = t.headlineLarge.g(FontWeight.Bold, -0.6f), headlineMedium = t.headlineMedium.g(FontWeight.Bold, -0.5f),
        headlineSmall = t.headlineSmall.g(FontWeight.Bold, -0.4f),
        titleLarge = t.titleLarge.g(FontWeight.Bold, -0.4f), titleMedium = t.titleMedium.g(FontWeight.Bold, -0.1f), titleSmall = t.titleSmall.g(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.g(), bodyMedium = t.bodyMedium.g(), bodySmall = t.bodySmall.g(),
        labelLarge = t.labelLarge.g(FontWeight.Bold, 0.1f), labelMedium = t.labelMedium.g(FontWeight.SemiBold), labelSmall = t.labelSmall.g(FontWeight.SemiBold),
    )
}

/** Soft, generous corners; buttons and chips are full pills. */
val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(dark: Boolean, accent: Accent = Accent.AMBER, content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = if (accent == Accent.DYNAMIC && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val a = Color(if (accent == Accent.DYNAMIC) Accent.AMBER.argb else accent.argb)
        if (dark) darkScheme(a) else lightScheme(a)
    }
    MaterialTheme(colorScheme = scheme, typography = AppType, shapes = AppShapes, content = content)
}

/** The signature element: a thin horizontal anamorphic lens flare. */
@Composable
fun FlareLine(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val c = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(
            Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 0.9f * alpha), c.copy(alpha = 0f))),
            Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
        )
    }
}

val Mono = TextStyle(fontFamily = MonoFamily, fontFeatureSettings = "tnum")
