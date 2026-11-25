package com.cinematy.video.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Blended modern palettes. No green anywhere.
val Blue = Color(0xFF8B9DFF)
val Violet = Color(0xFFB388FF)
val Pink = Color(0xFFFF8AB3)
val Orange = Color(0xFFFFAB70)
val Ink = Color(0xFF0B0D12)
val SurfaceDark = Color(0xFF14171F)
val SurfaceHigh = Color(0xFF1C202B)
val OnInk = Color(0xFFEDEEF4)
val OnInkDim = Color(0xFF9BA1B5)

class Palette(
    val darkScheme: ColorScheme,
    val lightScheme: ColorScheme,
    val darkBg: List<Color>,
    val lightBg: List<Color>,
    // 3 accent colors: nav bar gradient, header brush, player slider
    val accents: List<Color>,
)

val LocalBgGradient = compositionLocalOf { listOf(Color(0xFF241741), Color(0xFF2A1340), Color(0xFF120B24)) }
val LocalAccents = compositionLocalOf { listOf(Color(0xFF7C4DFF), Color(0xFFB388FF), Color(0xFFE040FB)) }

object UiThemeState {
    // 0 auto, 1 dark, 2 light - mirrors Prefs.theme; set at launch and from Settings
    var themePref by mutableStateOf(0)
    // 0 violet, 1 ocean, 2 sunset, 3 rose - mirrors Prefs.palette
    var palettePref by mutableStateOf(0)
}

private fun darkScheme(p: Color, pc: Color, opc: Color, s: Color, sc: Color, osc: Color, t: Color, tc: Color, otc: Color) =
    darkColorScheme(
        primary = p, onPrimary = Ink, primaryContainer = pc, onPrimaryContainer = opc,
        secondary = s, onSecondary = Ink, secondaryContainer = sc, onSecondaryContainer = osc,
        tertiary = t, onTertiary = Ink, tertiaryContainer = tc, onTertiaryContainer = otc,
        background = Ink, onBackground = OnInk,
        surface = SurfaceDark, onSurface = OnInk,
        surfaceVariant = SurfaceHigh, onSurfaceVariant = OnInkDim,
        outline = Color(0xFF3A3F50),
    )

private fun lightScheme(p: Color, pc: Color, opc: Color, s: Color, sc: Color, osc: Color, t: Color, tc: Color, otc: Color) =
    lightColorScheme(
        primary = p, onPrimary = Color.White, primaryContainer = pc, onPrimaryContainer = opc,
        secondary = s, onSecondary = Color.White, secondaryContainer = sc, onSecondaryContainer = osc,
        tertiary = t, onTertiary = Color.White,
        tertiaryContainer = tc, onTertiaryContainer = otc,
        background = Color(0xFFF7F6FC), onBackground = Color(0xFF1B1C24),
        surface = Color(0xFFF7F6FC), onSurface = Color(0xFF1B1C24),
        surfaceVariant = Color(0xFFE8E6F2), onSurfaceVariant = Color(0xFF5C5E70),
        outline = Color(0xFFC9C7DC),
    )

val Palettes = listOf(
    // 0: Violet (the original violet -> magenta look)
    Palette(
        darkScheme = darkScheme(
            Blue, Color(0xFF2E3550), Color(0xFFDDE1FF),
            Violet, Color(0xFF3A2D52), Color(0xFFEADDFF),
            Pink, Color(0xFF4E2A38), Color(0xFFFFD9E3),
        ),
        lightScheme = lightScheme(
            Color(0xFF5E54D6), Color(0xFFE4E0FF), Color(0xFF18124E),
            Color(0xFF8E3BD0), Color(0xFFF1DAFF), Color(0xFF2E0A4E),
            Color(0xFFD63384), Color(0xFFFFD9E8), Color(0xFF3E001E),
        ),
        darkBg = listOf(Color(0xFF241741), Color(0xFF2A1340), Color(0xFF120B24)),
        lightBg = listOf(Color(0xFFF5F3FC), Color(0xFFEFEBF9), Color(0xFFE6E2F5)),
        accents = listOf(Color(0xFF7C4DFF), Color(0xFFB388FF), Color(0xFFE040FB)),
    ),
    // 1: Ocean (blue -> cyan)
    Palette(
        darkScheme = darkScheme(
            Color(0xFF82B4FF), Color(0xFF1E3252), Color(0xFFD8E6FF),
            Color(0xFF67E8F9), Color(0xFF123A44), Color(0xFFC9F5FC),
            Color(0xFF9DA8FF), Color(0xFF2A2F52), Color(0xFFE0E3FF),
        ),
        lightScheme = lightScheme(
            Color(0xFF3D5AFE), Color(0xFFDDE4FF), Color(0xFF00105C),
            Color(0xFF0091B8), Color(0xFFD2F1F9), Color(0xFF003543),
            Color(0xFF5C6BC0), Color(0xFFE2E5F7), Color(0xFF131A4E),
        ),
        darkBg = listOf(Color(0xFF101C3D), Color(0xFF0B2540), Color(0xFF081226)),
        lightBg = listOf(Color(0xFFF0F6FF), Color(0xFFE9F3FD), Color(0xFFE2ECFA)),
        accents = listOf(Color(0xFF3D5AFE), Color(0xFF40C4FF), Color(0xFF00B8D4)),
    ),
    // 2: Sunset (orange -> red)
    Palette(
        darkScheme = darkScheme(
            Color(0xFFFFAB70), Color(0xFF4E2C1A), Color(0xFFFFE0C9),
            Color(0xFFFF8A80), Color(0xFF4E1F1F), Color(0xFFFFDAD6),
            Color(0xFFFFC194), Color(0xFF4E3320), Color(0xFFFFE8D4),
        ),
        lightScheme = lightScheme(
            Color(0xFFE65100), Color(0xFFFFE0CC), Color(0xFF4A1A00),
            Color(0xFFD84315), Color(0xFFFFDAD2), Color(0xFF4A0E00),
            Color(0xFFB23A48), Color(0xFFFFDDE1), Color(0xFF400012),
        ),
        darkBg = listOf(Color(0xFF2E1420), Color(0xFF33110B), Color(0xFF1A0A12)),
        lightBg = listOf(Color(0xFFFFF6F0), Color(0xFFFDEEE6), Color(0xFFFAE6DE)),
        accents = listOf(Color(0xFFFF6D00), Color(0xFFFF9E40), Color(0xFFFF5252)),
    ),
    // 3: Rose (pink -> gold)
    Palette(
        darkScheme = darkScheme(
            Color(0xFFFF8AB3), Color(0xFF4E2233), Color(0xFFFFD9E3),
            Color(0xFFFFD180), Color(0xFF4E3A14), Color(0xFFFFECC9),
            Color(0xFFF48FB1), Color(0xFF4A2436), Color(0xFFFFDCE6),
        ),
        lightScheme = lightScheme(
            Color(0xFFC2185B), Color(0xFFFFD9E5), Color(0xFF4A0020),
            Color(0xFF9A7400), Color(0xFFFFEFC4), Color(0xFF3A2C00),
            Color(0xFFD81B60), Color(0xFFFFDEE8), Color(0xFF44001E),
        ),
        darkBg = listOf(Color(0xFF2B1020), Color(0xFF331224), Color(0xFF1B0A18)),
        lightBg = listOf(Color(0xFFFDF2F7), Color(0xFFFAEBF2), Color(0xFFF5E4EE)),
        accents = listOf(Color(0xFFEC407A), Color(0xFFF48FB1), Color(0xFFFFCA66)),
    ),
)

@Composable
fun CinematyTheme(content: @Composable () -> Unit) {
    val dark = when (UiThemeState.themePref) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    val palette = Palettes[UiThemeState.palettePref.coerceIn(0, Palettes.size - 1)]
    CompositionLocalProvider(
        LocalBgGradient provides (if (dark) palette.darkBg else palette.lightBg),
        LocalAccents provides palette.accents,
    ) {
        MaterialTheme(
            colorScheme = if (dark) palette.darkScheme else palette.lightScheme,
            content = content,
        )
    }
}
