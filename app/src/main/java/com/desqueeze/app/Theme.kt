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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Anamorphic-flare blue on graphite. One accent, used sparingly. */
val Flare = Color(0xFF5AA9FF)

val DarkScheme = darkColorScheme(
    primary = Flare, onPrimary = Color(0xFF03203D),
    primaryContainer = Color(0xFF1C3552), onPrimaryContainer = Color(0xFFD3E6FF),
    secondaryContainer = Color(0xFF1C3552), onSecondaryContainer = Color(0xFFD3E6FF),
    background = Color(0xFF141A22), onBackground = Color(0xFFE6EBF1),
    surface = Color(0xFF141A22), onSurface = Color(0xFFE6EBF1),
    surfaceVariant = Color(0xFF1D2530), onSurfaceVariant = Color(0xFF8E99A8),
    surfaceContainerLow = Color(0xFF18202A), surfaceContainer = Color(0xFF1B232E),
    surfaceContainerHigh = Color(0xFF222B37), surfaceContainerHighest = Color(0xFF29333F),
    outline = Color(0xFF3A4554), outlineVariant = Color(0xFF2A3340),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF3B1F24), onErrorContainer = Color(0xFFFFD9D6),
)
val LightScheme = lightColorScheme(
    primary = Color(0xFF1C6FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF), onPrimaryContainer = Color(0xFF0A2E57),
    secondaryContainer = Color(0xFFDCEBFF), onSecondaryContainer = Color(0xFF0A2E57),
    background = Color(0xFFF3F5F8), onBackground = Color(0xFF151B23),
    surface = Color(0xFFF3F5F8), onSurface = Color(0xFF151B23),
    surfaceVariant = Color(0xFFE6EAF0), onSurfaceVariant = Color(0xFF5B6675),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECEFF4), surfaceContainerHighest = Color(0xFFE3E8EE),
    outline = Color(0xFFC3CBD6), outlineVariant = Color(0xFFDCE2EA),
)

val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppType, content = content)

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
