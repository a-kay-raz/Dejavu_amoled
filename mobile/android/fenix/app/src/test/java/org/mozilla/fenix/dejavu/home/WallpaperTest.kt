/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.compose.base.theme.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper

@RunWith(AndroidJUnit4::class)
class WallpaperTest {
    private val covers = mapOf(0 to 0.5f, 2 to 0.7f)

    private fun coverOf(page: Int) = covers[page] ?: 1f

    @Test
    fun `a page with a wallpaper is covered by its veil`() {
        assertEquals(0.5f, coverAt(0f, ::coverOf), DELTA)
        assertEquals(0.7f, coverAt(2f, ::coverOf), DELTA)
    }

    @Test
    fun `a page without a wallpaper is fully covered, so its theme shows as it is`() {
        assertEquals(1f, coverAt(1f, ::coverOf), DELTA)
        assertEquals(1f, coverAt(3f, ::coverOf), DELTA)
    }

    @Test
    fun `swiping goes from the cover of one page to the cover of the next`() {
        assertEquals(0.75f, coverAt(0.5f, ::coverOf), DELTA)
        assertEquals(1f - 0.3f / 4, coverAt(1.25f, ::coverOf), DELTA)
    }

    @Test
    fun `a veil covers a white picture just enough for light text to read on it`() {
        val alpha = readableVeil(shade = 1f, veil = 0f, text = 1f)

        assertTrue(alpha in 0.5f..0.95f)
        assertTrue(contrast(alpha) >= 5.5f)
        assertTrue(contrast(alpha - 0.02f) < 5.5f)
    }

    @Test
    fun `light text on a dark picture needs no veil`() {
        assertEquals(0f, readableVeil(shade = 0.01f, veil = 0f, text = 1f), DELTA)
    }

    @Test
    fun `a lighter picture gets a stronger veil`() {
        val light = veilOf(WorkspaceWallpaper("a.jpg", light = 0.9f, dark = 0.2f), DARK_SURFACE, LIGHT_TEXT)
        val dim = veilOf(WorkspaceWallpaper("b.jpg", light = 0.2f, dark = 0.02f), DARK_SURFACE, LIGHT_TEXT)

        assertTrue(light.alpha > dim.alpha)
    }

    @Test
    fun `under dark text the darkest parts of the picture count`() {
        val dark = veilOf(WorkspaceWallpaper("a.jpg", light = 0.9f, dark = 0.01f), LIGHT_SURFACE, DARK_TEXT)
        val light = veilOf(WorkspaceWallpaper("b.jpg", light = 0.9f, dark = 0.6f), LIGHT_SURFACE, DARK_TEXT)

        assertTrue(dark.alpha > light.alpha)
    }

    @Test
    fun `a more intense wallpaper shows more of its picture, within bounds`() {
        val picture = WorkspaceWallpaper("a.jpg", light = 0.5f, dark = 0.1f)
        val usual = veilOf(picture, DARK_SURFACE, LIGHT_TEXT).alpha
        val clear = veilOf(picture.copy(intensity = 1f), DARK_SURFACE, LIGHT_TEXT).alpha
        val veiled = veilOf(picture.copy(intensity = 0f), DARK_SURFACE, LIGHT_TEXT).alpha

        assertTrue(clear < usual)
        assertTrue(veiled > usual)
        assertTrue(clear >= 0.15f && veiled <= 0.92f)
    }

    @Test
    fun `a picture whose shades are not known yet gets a usual veil`() {
        assertEquals(0.6f, veilOf(WorkspaceWallpaper("a.jpg"), DARK_SURFACE, LIGHT_TEXT).alpha, DELTA)
    }

    @Test
    fun `the veil takes the colors of the picture at the lightness of the background`() {
        val veil = veilOf(WorkspaceWallpaper("a.jpg", colors = listOf(RED, BLUE)), DARK_SURFACE, LIGHT_TEXT)

        assertEquals(2, veil.colors.size)
        veil.colors.forEach { assertEquals(DARK_SURFACE.luminance(), it.luminance(), 0.01f) }
        assertTrue(veil.colors[0].red > veil.colors[0].blue)
        assertTrue(veil.colors[1].blue > veil.colors[1].red)
    }

    @Test
    fun `a picture without colors is veiled in the plain background`() {
        assertEquals(listOf(DARK_SURFACE), veilOf(WorkspaceWallpaper("a.jpg"), DARK_SURFACE, LIGHT_TEXT).colors)
    }

    @Test
    fun `a wallpaper shows in its dark look whatever the app's theme, unless its light look was picked`() {
        val dark = WorkspaceWallpaper("a.jpg")
        val light = WorkspaceWallpaper("b.jpg", lightLook = true)

        assertEquals(Theme.Dark, wallpaperTheme(dark, Theme.Light))
        assertEquals(Theme.Dark, wallpaperTheme(dark, Theme.Dark))
        assertEquals(Theme.Light, wallpaperTheme(light, Theme.Light))
        assertEquals(Theme.Light, wallpaperTheme(light, Theme.Dark))
    }

    @Test
    fun `the dark look keeps the black of the OLED theme`() {
        assertEquals(Theme.Oled, wallpaperTheme(WorkspaceWallpaper("a.jpg"), Theme.Oled))
        assertEquals(Theme.Light, wallpaperTheme(WorkspaceWallpaper("a.jpg", lightLook = true), Theme.Oled))
    }

    @Test
    fun `the veil is as dark in the light theme as in the dark one, and pale only in the light look`() {
        val picture = WorkspaceWallpaper("a.jpg", colors = listOf(RED, BLUE), light = 0.6f, dark = 0.1f)
        val inLight = veilOf(picture, Theme.Light)
        val inDark = veilOf(picture, Theme.Dark)
        val pale = veilOf(picture.copy(lightLook = true), Theme.Dark)

        assertEquals(inDark.colors, inLight.colors)
        assertEquals(inDark.alpha, inLight.alpha, DELTA)
        assertTrue(inLight.colors.all { it.luminance() < 0.2f })
        assertTrue(pale.colors.all { it.luminance() > 0.5f })
    }

    /** The contrast of white text over white under a black veil of [alpha], blended in sRGB. */
    private fun contrast(alpha: Float): Float {
        val srgb = 1f - alpha
        val shown = if (srgb <= 0.04045f) srgb / 12.92f else Math.pow((srgb + 0.055) / 1.055, 2.4).toFloat()
        return 1.05f / (shown + 0.05f)
    }

    private companion object {
        const val DELTA = 0.001f
        val DARK_SURFACE = Color(0xFF1F1B11)
        val LIGHT_TEXT = Color(0xFFF5F0E6)
        val LIGHT_SURFACE = Color(0xFFFBF6E8)
        val DARK_TEXT = Color(0xFF1F1B13)
        val RED = 0xFFE53935.toInt()
        val BLUE = 0xFF1E88E5.toInt()
    }
}
