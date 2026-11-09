package com.desqueeze.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
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

/** Ice blue: the brand accent and default. */
val Flare = Color(0xFF7CCBFF)

/**
 * Accent colours over "liquid glass": translucent, ice-tinted panels floating over a softly blurred copy of the clip
 * you're editing (see [GlassBackdrop]). Every accent shade is derived from one colour.
 */
enum class Accent(val label: String, val argb: Long) {
    ICE("Ice", 0xFF7CCBFF), AMBER("Amber", 0xFFF2A33A), MINT("Mint", 0xFF4FD8A4), CYAN("Cyan", 0xFF3DD6E0),
    VIOLET("Violet", 0xFFA98BFF), ROSE("Rose", 0xFFFF7FA3), MONO("Mono", 0xFFE4E6EA),
    DYNAMIC("Wallpaper", 0),
}

private fun onColor(c: Color) = if (c.luminance() > 0.40f) Color(0xFF04121F) else Color.White

/** Glass tint: the panel colour seen through, as a fraction of white-ice. */
private fun glass(alpha: Float) = Color(0xFFC8E6FF).copy(alpha = alpha)

fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent, onPrimary = onColor(accent),
    primaryContainer = lerp(Color(0xFF0E1724), accent, 0.26f), onPrimaryContainer = lerp(accent, Color.White, 0.6f),
    secondary = lerp(accent, Color(0xFFA9B8C9), 0.5f), onSecondary = Color(0xFF04121F),
    secondaryContainer = lerp(Color(0xFF0E1724), accent, 0.26f), onSecondaryContainer = lerp(accent, Color.White, 0.6f),
    tertiary = Color(0xFFEEF6FF), onTertiary = Color(0xFF04121F),
    background = Color(0xFF05090F), onBackground = Color(0xFFEEF6FF),
    surface = Color(0xFF05090F), onSurface = Color(0xFFEEF6FF),
    // Panels and cards are glass (the backdrop glows through); menus and dialogs stay solid so they're readable.
    surfaceVariant = glass(0.12f), onSurfaceVariant = Color(0xFFAFC0D2),
    surfaceContainerLowest = glass(0.04f), surfaceContainerLow = glass(0.07f),
    surfaceContainer = glass(0.10f), surfaceContainerHigh = Color(0xFF142131), surfaceContainerHighest = Color(0xFF1B2A3C),
    surfaceTint = accent,
    outline = glass(0.36f), outlineVariant = glass(0.20f),
    inverseSurface = Color(0xFFEEF6FF), inverseOnSurface = Color(0xFF0E1724),
    error = Color(0xFFFF8A80), onError = Color(0xFF1A0505), errorContainer = Color(0xFF3A1616), onErrorContainer = Color(0xFFFFDAD6),
)

fun lightScheme(accent: Color): ColorScheme {
    // Darken the accent just enough for text and icons on frosted white to reach WCAG AA (4.5:1).
    fun contrastOnWhite(c: Color) = 1.05f / (c.luminance() + 0.05f)
    var p = accent; var i = 0
    while (contrastOnWhite(p) < 4.6f && i++ < 30) p = lerp(p, Color.Black, 0.06f)
    val frost = Color.White
    return lightColorScheme(
        primary = p, onPrimary = onColor(p),
        primaryContainer = lerp(Color.White, accent, 0.26f), onPrimaryContainer = lerp(p, Color.Black, 0.45f),
        secondaryContainer = lerp(Color.White, accent, 0.26f), onSecondaryContainer = lerp(p, Color.Black, 0.45f),
        background = Color(0xFFE9F0F7), onBackground = Color(0xFF0E1726),
        surface = Color(0xFFE9F0F7), onSurface = Color(0xFF0E1726),
        surfaceVariant = frost.copy(alpha = 0.55f), onSurfaceVariant = Color(0xFF475668),
        surfaceContainerLowest = frost.copy(alpha = 0.35f), surfaceContainerLow = frost.copy(alpha = 0.45f),
        surfaceContainer = frost.copy(alpha = 0.6f), surfaceContainerHigh = Color(0xFFF6F9FC), surfaceContainerHighest = Color(0xFFEDF3F9),
        outline = Color(0xFF0A2540).copy(alpha = 0.22f), outlineVariant = Color(0xFF0A2540).copy(alpha = 0.12f),
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
fun AppTheme(dark: Boolean, accent: Accent = Accent.ICE, content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = if (accent == Accent.DYNAMIC && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val a = Color(if (accent == Accent.DYNAMIC) Accent.ICE.argb else accent.argb)
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

/**
 * What the glass floats over: the clip you're working on, heavily blurred and dimmed (Android 12+), with soft
 * ice-coloured light behind it. Older phones get the light alone.
 */
@Composable
fun GlassBackdrop(image: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < 0.3f
    Box(modifier.fillMaxSize().background(c.background)) {
        Crossfade(image, Modifier.fillMaxSize(), animationSpec = tween(600), label = "backdrop") { img ->
            if (img != null && android.os.Build.VERSION.SDK_INT >= 31) Image(img, null,
                Modifier.fillMaxSize().blur(70.dp).alpha(if (dark) 0.5f else 0.4f), contentScale = ContentScale.Crop)
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            fun glow(col: Color, a: Float, cx: Float, cy: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
            glow(c.primary, 0.30f, w * 0.05f, h * 0.30f, w * 0.75f)
            glow(Color(0xFF6E64FF), 0.22f, w * 1.0f, h * 0.55f, w * 0.75f)
            glow(Color(0xFF28C8DC), if (dark) 0.18f else 0.22f, w * 0.45f, h * 1.02f, w * 0.75f)
            // A gentle scrim keeps text crisp over bright footage.
            drawRect(Brush.verticalGradient(listOf(c.background.copy(alpha = if (dark) 0.25f else 0.2f), c.background.copy(alpha = if (dark) 0.45f else 0.35f))))
        }
    }
}
