/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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
 * Dejavu's golden yellow look, on gold tinted charcoal in the dark themes and gold tinted paper in the light one. Private browsing
 * keeps Firefox's purple. The same colors are in `dejavu_colors.xml` for the screens made of views.
 */
object DejavuColors {
    private val Gold10 = Color(0xFF261A00)
    private val Gold20 = Color(0xFF3F2E00)
    private val Gold30 = Color(0xFF5C4200)
    private val Gold45 = Color(0xFF8C6300)
    private val Gold80 = Color(0xFFFFC83D)
    private val Gold90 = Color(0xFFFFDF9E)

    private val Sand10 = Color(0xFF2A2010)
    private val Sand20 = Color(0xFF3D2E14)
    private val Sand30 = Color(0xFF4A3F2C)
    private val Sand35 = Color(0xFF332A1C)
    private val Sand80 = Color(0xFFDDC7A0)
    private val Sand90 = Color(0xFFF3E5C8)

    private val Amber20 = Color(0xFF472A00)
    private val Amber40 = Color(0xFF9C5700)
    private val Amber90 = Color(0xFF2E1500)
    private val Amber80 = Color(0xFFF5BD7A)
    private val Amber95 = Color(0xFFFFF1DC)

    private val Warm0 = Color(0xFFFFFBF5)
    private val Warm10 = Color(0xFFF2EDE4)
    private val Warm15 = Color(0xFFE8E2D7)
    private val Warm20 = Color(0xFFD9D2C6)
    private val Warm25 = Color(0xFFCAC3B7)
    private val Warm30 = Color(0xFFB9B2A6)
    private val Warm40 = Color(0xFF978F83)
    private val Warm45 = Color(0xFF827B70)
    private val Warm50 = Color(0xFF686157)
    private val Warm55 = Color(0xFF524C44)
    private val Warm60 = Color(0xFF403B34)
    private val Warm65 = Color(0xFF322E28)
    private val Warm70 = Color(0xFF26231E)
    private val Warm80 = Color(0xFF181613)
    private val Warm85 = Color(0xFF13120F)
    private val Warm90 = Color(0xFF12110E)

    private val Gilt5 = Color(0xFFFBF6E8)
    private val Gilt75 = Color(0xFF1F1B11)

    private val Ink = Color(0xFF1F1B13)
    private val InkA70 = Color(0xB21F1B13)
    private val Paper = Color(0xFFF5F0E6)
    private val PaperA70 = Color(0xB2F5F0E6)
    private val Scrim = Color(0x80000000)
    private val White = Color(0xFFFFFFFF)
    private val Black = Color(0xFF000000)

    /** Dejavu's Material colors for [theme], or `null` when Firefox's are kept. */
    fun colorScheme(theme: Theme): ColorScheme? = when (theme) {
        Theme.Light -> light
        Theme.Dark -> dark
        Theme.Oled -> oled
        Theme.Private -> null
    }

    /** Dejavu's gradients for [theme], such as the page loading bar, or `null` when Firefox's are kept. */
    fun gradients(theme: Theme): AcornGradientScheme? = when (theme) {
        Theme.Light -> lightGradients
        Theme.Dark, Theme.Oled -> darkGradients
        Theme.Private -> null
    }

    /** Dejavu's extra design tokens for [theme], or `null` when Firefox's are kept. */
    fun palette(theme: Theme): AcornColors? = when (theme) {
        Theme.Light -> lightPalette
        Theme.Dark -> darkPalette
        Theme.Oled -> oledPalette
        Theme.Private -> null
    }

    private fun goldGradient(from: Color, to: Color) = AcornGradient(
        type = AcornGradientType.Linear(angleInDegrees = GRADIENT_ANGLE),
        colorStops = listOf(ColorStop(0f, from), ColorStop(GRADIENT_END, to)),
    )

    private val lightGradients = goldGradient(Color(0xFFD9A100), Gold45).let {
        lightAcornGradientScheme.copy(accent = it, tabOutline = it)
    }

    private val darkGradients = goldGradient(Gold90, Gold80).let {
        darkAcornGradientScheme.copy(accent = it, tabOutline = it)
    }

    private val lightPalette = lightColorPalette.copy(
        information = Gold45,
        onInformation = White,
        informationContainer = Gold90,
        onInformationContainer = Gold10,
        surfaceDimVariant = Warm10,
        surfaceContainerSelected = Warm25,
        autofillText = Warm30,
        selectedText = Warm25,
        sheetOutline = Gilt5,
    )

    private val darkPalette = darkColorPalette.copy(
        information = Gold80,
        onInformation = Gold20,
        informationContainer = Gold30,
        onInformationContainer = Gold90,
        surfaceDimVariant = Black,
        surfaceContainerSelected = Color(0xFF141414),
        autofillText = Sand80.copy(alpha = AUTOFILL_ALPHA),
        selectedText = Color(0xFF262626),
        sheetOutline = Color(0xFF1A1A1A),
    )

    private val oledPalette = oledColorPalette.copy(
        information = Gold80,
        onInformation = Gold20,
        informationContainer = Gold30,
        onInformationContainer = Gold90,
        surfaceDimVariant = Black,
        surfaceContainerSelected = Color(0xFF141414),
        autofillText = Sand80.copy(alpha = AUTOFILL_ALPHA),
        selectedText = Color(0xFF262626),
        sheetOutline = Color(0xFF1A1A1A),
    )

    private val dark: ColorScheme = darkColorScheme(
        primary = Gold80,
        onPrimary = Gold20,
        primaryContainer = Gold30,
        onPrimaryContainer = Gold90,
        // Snackbars and tooltips are black in the dark themes, with gold buttons.
        inversePrimary = Gold80,
        secondary = Sand80,
        onSecondary = Sand20,
        secondaryContainer = Sand30,
        onSecondaryContainer = Sand90,
        tertiary = Amber80,
        onTertiary = Amber20,
        tertiaryContainer = Sand35,
        onTertiaryContainer = Paper,
        background = Black,
        onBackground = Paper,
        surface = Black,
        onSurface = Paper,
        surfaceVariant = Color(0xFF0F0F0F),
        onSurfaceVariant = PaperA70,
        surfaceTint = Black,
        inverseSurface = Black,
        inverseOnSurface = Paper,
        error = Color(0xFFFF8090),
        onError = Warm80,
        errorContainer = Color(0xFF69172D),
        onErrorContainer = Paper,
        outline = Color(0xFF2A2A2A),
        outlineVariant = Color(0xFF1A1A1A),
        scrim = Scrim,
        surfaceBright = Color(0xFF141414),
        surfaceDim = Black,
        surfaceContainer = Black,
        surfaceContainerHigh = Color(0xFF0A0A0A),
        surfaceContainerHighest = Color(0xFF121212),
        surfaceContainerLow = Black,
        surfaceContainerLowest = Black,
    )

    private val oled: ColorScheme = dark.copy(
        onPrimaryContainer = Gold90,
        secondaryContainer = Sand30,
        onSecondaryContainer = Sand90,
        tertiaryContainer = Sand35,
        onTertiaryContainer = Paper,
        background = Black,
        onBackground = Paper,
        surface = Black,
        onSurface = Paper,
        onSurfaceVariant = PaperA70,
        errorContainer = Color(0xFF42121F),
        onErrorContainer = Paper,
        outline = Color(0xFF2A2A2A),
        outlineVariant = Color(0xFF1A1A1A),
        surfaceBright = Color(0xFF141414),
        surfaceDim = Black,
        surfaceContainer = Black,
        surfaceContainerHigh = Color(0xFF0A0A0A),
        surfaceContainerHighest = Color(0xFF121212),
        surfaceContainerLow = Black,
        surfaceContainerLowest = Black,
    )

    private val light: ColorScheme = lightColorScheme(
        primary = Gold45,
        onPrimary = White,
        primaryContainer = Gold90,
        onPrimaryContainer = Gold10,
        inversePrimary = Gold80,
        secondary = Warm50,
        onSecondary = White,
        secondaryContainer = Sand90,
        onSecondaryContainer = Sand10,
        tertiary = Amber40,
        onTertiary = White,
        tertiaryContainer = Amber95,
        onTertiaryContainer = Amber90,
        background = Gilt5,
        onBackground = Ink,
        surface = Gilt5,
        onSurface = Ink,
        surfaceVariant = Warm15,
        onSurfaceVariant = InkA70,
        surfaceTint = Warm30,
        inverseSurface = Warm70,
        inverseOnSurface = White,
        error = Color(0xFFC52D4F),
        onError = White,
        errorContainer = Color(0xFFFFD0D7),
        onErrorContainer = Ink,
        outline = Warm45,
        outlineVariant = Warm15,
        scrim = Scrim,
        surfaceBright = White,
        surfaceDim = Warm15,
        surfaceContainer = Gilt5,
        surfaceContainerHigh = Warm10,
        surfaceContainerHighest = Warm15,
        surfaceContainerLow = Warm0,
        surfaceContainerLowest = White,
    )

    private const val AUTOFILL_ALPHA = 0.55f
    private const val GRADIENT_ANGLE = 96f
    private const val GRADIENT_END = 0.71f
}
