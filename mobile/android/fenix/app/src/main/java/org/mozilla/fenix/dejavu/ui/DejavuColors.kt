/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import mozilla.components.compose.base.theme.AcornColors
import mozilla.components.compose.base.theme.AcornGradient
import mozilla.components.compose.base.theme.AcornGradientScheme
import mozilla.components.compose.base.theme.AcornGradientType
import mozilla.components.compose.base.theme.Theme
import mozilla.components.compose.base.theme.darkAcornGradientScheme
import mozilla.components.compose.base.theme.darkColorPalette
import mozilla.components.compose.base.theme.lightAcornGradientScheme
import mozilla.components.compose.base.theme.lightColorPalette
import mozilla.components.compose.base.theme.oledColorPalette
import mozilla.components.compose.base.utils.ColorStop

/**
 * Dejavu's neutral look: dark grays in the dark themes and light ones in the light theme, with a near white or near
 * black accent, so the colors come from the themes and wallpapers of the workspaces, or with the accent picked in
 * Customize, see [DejavuAccent]. Private browsing keeps Firefox's purple. The same colors are in `dejavu_colors.xml`
 * for the screens made of views; each gray is named after its lightness, from 0 for black to 100.
 */
object DejavuColors {
    private val Gray6 = Color(0xFF121212)
    private val Gray7 = Color(0xFF161616)
    private val Gray10 = Color(0xFF1B1B1B)
    private val Gray10A70 = Color(0xB21B1B1B)
    private val Gray14 = Color(0xFF232323)
    private val Gray18 = Color(0xFF2B2B2B)
    private val Gray19 = Color(0xFF2E2E2E)
    private val Gray20 = Color(0xFF303030)
    private val Gray25 = Color(0xFF3C3C3C)
    private val Gray27 = Color(0xFF404040)
    private val Gray30 = Color(0xFF464646)
    private val Gray33 = Color(0xFF4D4D4D)
    private val Gray42 = Color(0xFF626262)
    private val Gray45 = Color(0xFF6A6A6A)
    private val Gray52 = Color(0xFF7C7C7C)
    private val Gray60 = Color(0xFF909090)
    private val Gray73 = Color(0xFFB3B3B3)
    private val Gray79 = Color(0xFFC4C4C4)
    private val Gray81 = Color(0xFFC9C9C9)
    private val Gray84 = Color(0xFFD1D1D1)
    private val Gray90 = Color(0xFFE3E3E3)
    private val Gray91 = Color(0xFFE6E6E6)
    private val Gray94 = Color(0xFFEDEDED)
    private val Gray95 = Color(0xFFF0F0F0)
    private val Gray95A70 = Color(0xB2F0F0F0)
    private val Gray96 = Color(0xFFF3F3F3)
    private val Gray97 = Color(0xFFF6F6F6)
    private val Gray99 = Color(0xFFFBFBFB)

    private val Scrim = Color(0x80000000)
    private val White = Color(0xFFFFFFFF)
    private val Black = Color(0xFF000000)

    /** Dejavu's Material colors for [theme], or `null` when Firefox's are kept. */
    fun colorScheme(theme: Theme): ColorScheme? {
        val neutral = when (theme) {
            Theme.Light -> light
            Theme.Dark -> dark
            Theme.Oled -> oled
            Theme.Private -> return null
        }
        val tones = DejavuAccent.tones ?: return neutral
        return accented(tones).schemes.getOrPut(theme) { neutral.withAccent(tones.of(theme), tones.dark.primary) }
    }

    /** Dejavu's gradients for [theme], such as the page loading bar, or `null` when Firefox's are kept. */
    fun gradients(theme: Theme): AcornGradientScheme? {
        val neutral = when (theme) {
            Theme.Light -> lightGradients
            Theme.Dark, Theme.Oled -> darkGradients
            Theme.Private -> return null
        }
        val tones = DejavuAccent.tones ?: return neutral
        return accented(tones).gradients.getOrPut(theme) {
            val primary = tones.of(theme).primary
            val end = lerp(primary, if (theme == Theme.Light) Black else White, GRADIENT_SHADE)
            accentGradient(primary, end).let { neutral.copy(accent = it, tabOutline = it) }
        }
    }

    // The schemes made for the accent picked last, by theme, so a theme recomposing takes the same ones again.
    private class Accented(val tones: AccentTones) {
        val schemes = mutableMapOf<Theme, ColorScheme>()
        val gradients = mutableMapOf<Theme, AcornGradientScheme>()
    }

    private var accentedLast: Accented? = null

    private fun accented(tones: AccentTones): Accented =
        accentedLast?.takeIf { it.tones == tones } ?: Accented(tones).also { accentedLast = it }

    private fun AccentTones.of(theme: Theme) = if (theme == Theme.Light) light else dark

    // The accent takes the place of the near white or near black: what stands out, switches, which Firefox draws in
    // the tertiary color, and the buttons of snackbars, which are dark in every theme.
    private fun ColorScheme.withAccent(tone: AccentTone, inverse: Color): ColorScheme {
        val onAccent = if (tone.primary.luminance() > ON_ACCENT_LUMINANCE) Gray10 else White
        return copy(
            primary = tone.primary,
            onPrimary = onAccent,
            primaryContainer = tone.container,
            inversePrimary = inverse,
            tertiary = tone.primary,
            onTertiary = onAccent,
        )
    }

    /** Dejavu's extra design tokens for [theme], or `null` when Firefox's are kept. */
    fun palette(theme: Theme): AcornColors? = when (theme) {
        Theme.Light -> lightPalette
        Theme.Dark -> darkPalette
        Theme.Oled -> oledPalette
        Theme.Private -> null
    }

    private fun accentGradient(from: Color, to: Color) = AcornGradient(
        type = AcornGradientType.Linear(angleInDegrees = GRADIENT_ANGLE),
        colorStops = listOf(ColorStop(0f, from), ColorStop(GRADIENT_END, to)),
    )

    private val lightGradients = accentGradient(Gray45, Gray19).let {
        lightAcornGradientScheme.copy(accent = it, tabOutline = it)
    }

    private val darkGradients = accentGradient(Gray95, Gray81).let {
        darkAcornGradientScheme.copy(accent = it, tabOutline = it)
    }

    private val lightPalette = lightColorPalette.copy(
        information = Gray19,
        onInformation = White,
        informationContainer = Gray90,
        onInformationContainer = Gray10,
        surfaceDimVariant = Gray94,
        surfaceContainerSelected = Gray79,
        autofillText = Gray73,
        selectedText = Gray79,
        sheetOutline = Gray97,
    )

    private val darkPalette = darkColorPalette.copy(
        information = Gray90,
        onInformation = Gray20,
        informationContainer = Gray30,
        onInformationContainer = Gray90,
        surfaceDimVariant = Gray7,
        surfaceContainerSelected = Gray33,
        autofillText = Gray81.copy(alpha = AUTOFILL_ALPHA),
        selectedText = Gray52,
        sheetOutline = Gray10,
    )

    private val oledPalette = oledColorPalette.copy(
        information = Gray90,
        onInformation = Gray20,
        informationContainer = Gray30,
        onInformationContainer = Gray84,
        surfaceDimVariant = Gray6,
        surfaceContainerSelected = Gray19,
        autofillText = Gray27,
        selectedText = Gray25,
        sheetOutline = Gray19,
    )

    private val dark: ColorScheme = darkColorScheme(
        primary = Gray90,
        onPrimary = Gray10,
        primaryContainer = Gray30,
        onPrimaryContainer = Gray90,
        // Snackbars and tooltips are black in the dark themes, with light buttons.
        inversePrimary = Gray90,
        secondary = Gray81,
        onSecondary = Gray20,
        secondaryContainer = Gray27,
        onSecondaryContainer = Gray91,
        tertiary = Gray81,
        onTertiary = Gray20,
        tertiaryContainer = Gray18,
        onTertiaryContainer = Gray95,
        background = Gray10,
        onBackground = Gray95,
        surface = Gray10,
        onSurface = Gray95,
        surfaceVariant = Gray19,
        onSurfaceVariant = Gray95A70,
        surfaceTint = Gray42,
        inverseSurface = Black,
        inverseOnSurface = Gray95,
        error = Color(0xFFFF8090),
        onError = Gray7,
        errorContainer = Color(0xFF69172D),
        onErrorContainer = Gray95,
        outline = Gray52,
        outlineVariant = Gray25,
        scrim = Scrim,
        surfaceBright = Gray19,
        surfaceDim = Gray6,
        surfaceContainer = Gray10,
        surfaceContainerHigh = Gray14,
        surfaceContainerHighest = Gray19,
        surfaceContainerLow = Gray7,
        surfaceContainerLowest = Gray6,
    )

    private val oled: ColorScheme = dark.copy(
        onPrimaryContainer = Gray84,
        secondaryContainer = Gray18,
        onSecondaryContainer = Gray84,
        tertiaryContainer = Gray6,
        onTertiaryContainer = Gray84,
        background = Black,
        onBackground = Gray84,
        surface = Black,
        onSurface = Gray84,
        onSurfaceVariant = Gray60,
        errorContainer = Color(0xFF42121F),
        onErrorContainer = Gray84,
        outline = Gray42,
        outlineVariant = Gray19,
        surfaceBright = Gray7,
        surfaceDim = Black,
        surfaceContainer = Black,
        surfaceContainerHigh = Gray6,
        surfaceContainerHighest = Gray7,
        surfaceContainerLow = Gray6,
        surfaceContainerLowest = Black,
    )

    private val light: ColorScheme = lightColorScheme(
        primary = Gray19,
        onPrimary = White,
        primaryContainer = Gray90,
        onPrimaryContainer = Gray10,
        inversePrimary = Gray90,
        secondary = Gray42,
        onSecondary = White,
        secondaryContainer = Gray91,
        onSecondaryContainer = Gray14,
        tertiary = Gray45,
        onTertiary = White,
        tertiaryContainer = Gray96,
        onTertiaryContainer = Gray10,
        background = Gray97,
        onBackground = Gray10,
        surface = Gray97,
        onSurface = Gray10,
        surfaceVariant = Gray90,
        onSurfaceVariant = Gray10A70,
        surfaceTint = Gray73,
        inverseSurface = Gray14,
        inverseOnSurface = White,
        error = Color(0xFFC52D4F),
        onError = White,
        errorContainer = Color(0xFFFFD0D7),
        onErrorContainer = Gray10,
        outline = Gray52,
        outlineVariant = Gray90,
        scrim = Scrim,
        surfaceBright = White,
        surfaceDim = Gray90,
        surfaceContainer = Gray97,
        surfaceContainerHigh = Gray94,
        surfaceContainerHighest = Gray90,
        surfaceContainerLow = Gray99,
        surfaceContainerLowest = White,
    )

    private const val AUTOFILL_ALPHA = 0.55f
    private const val ON_ACCENT_LUMINANCE = 0.4f
    private const val GRADIENT_SHADE = 0.25f
    private const val GRADIENT_ANGLE = 96f
    private const val GRADIENT_END = 0.71f
}
