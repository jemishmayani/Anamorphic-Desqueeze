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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Anamorphic-flare blue: the brand accent and default. */
val Flare = Color(0xFF5AA9FF)

/**
 * Accent colours. Backgrounds stay neutral graphite (no colour cast that competes with the footage),
 * and every accent shade is derived from one colour. Amber is reserved for log/HDR badges.
 */
enum class Accent(val label: String, val argb: Long) {
    FLARE("Flare blue", 0xFF5AA9FF), CYAN("Cyan", 0xFF3DD6E0), MINT("Mint", 0xFF4FD8A4),
    VIOLET("Violet", 0xFFA98BFF), ROSE("Rose", 0xFFFF7FA3), MONO("Mono", 0xFFE4E6EA),
    DYNAMIC("Wallpaper", 0),
}

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF0B0D10) else Color.White

fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent, onPrimary = onColor(accent),
    primaryContainer = lerp(Color(0xFF16191F), accent, 0.24f), onPrimaryContainer = lerp(accent, Color.White, 0.6f),
    secondary = lerp(accent, Color(0xFF9AA1AB), 0.5f), onSecondary = Color(0xFF0B0D10),
    secondaryContainer = lerp(Color(0xFF16191F), accent, 0.24f), onSecondaryContainer = lerp(accent, Color.White, 0.6f),
    background = Color(0xFF0E1013), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0E1013), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1D2026), onSurfaceVariant = Color(0xFF9CA2AC),
    surfaceContainerLowest = Color(0xFF0A0B0E), surfaceContainerLow = Color(0xFF131519),
    surfaceContainer = Color(0xFF17191E), surfaceContainerHigh = Color(0xFF1E2127), surfaceContainerHighest = Color(0xFF252931),
    outline = Color(0xFF3A3F48), outlineVariant = Color(0xFF292D34),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF3B1F24), onErrorContainer = Color(0xFFFFD9D6),
)

fun lightScheme(accent: Color): ColorScheme {
    // Darken the accent just enough for text and icons on white to reach WCAG AA (4.5:1).
    fun contrastOnWhite(c: Color) = 1.05f / (c.luminance() + 0.05f)
    var p = accent; var i = 0
    while (contrastOnWhite(p) < 4.6f && i++ < 30) p = lerp(p, Color.Black, 0.06f)
    return lightColorScheme(
        primary = p, onPrimary = onColor(p),
        primaryContainer = lerp(Color.White, accent, 0.18f), onPrimaryContainer = lerp(p, Color.Black, 0.45f),
        secondaryContainer = lerp(Color.White, accent, 0.18f), onSecondaryContainer = lerp(p, Color.Black, 0.45f),
        background = Color(0xFFF5F6F8), onBackground = Color(0xFF14161A),
        surface = Color(0xFFF5F6F8), onSurface = Color(0xFF14161A),
        surfaceVariant = Color(0xFFE7E9ED), onSurfaceVariant = Color(0xFF5A606A),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDEFF2), surfaceContainerHighest = Color(0xFFE4E7EB),
        outline = Color(0xFFC4C8CF), outlineVariant = Color(0xFFDDE0E5),
    )
}

val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

@Composable
fun AppTheme(dark: Boolean, accent: Accent = Accent.FLARE, content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = if (accent == Accent.DYNAMIC && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val a = Color(if (accent == Accent.DYNAMIC) Accent.FLARE.argb else accent.argb)
        if (dark) darkScheme(a) else lightScheme(a)
    }
    MaterialTheme(colorScheme = scheme, typography = AppType, content = content)
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

val Mono = TextStyle(fontFeatureSettings = "tnum")
