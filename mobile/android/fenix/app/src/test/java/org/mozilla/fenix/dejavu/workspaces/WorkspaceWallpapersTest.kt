/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import java.io.File
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceWallpapersTest {
    private fun newStore(): WorkspaceWallpapers =
        WorkspaceWallpapers::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun newRepository(): WorkspaceRepository =
        WorkspaceRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun picture(name: String) =
        File(File(testContext.filesDir, "dejavu_wallpapers"), name).apply {
            parentFile?.mkdirs()
            writeText(name)
        }

    private fun awaitGone(file: File) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(file.exists())
    }

    /** A store whose cleaning up of pictures left from before is over, so it leaves new ones alone. */
    private fun cleanStore(): WorkspaceWallpapers {
        val left = picture("left.jpg")
        return newStore().also { awaitGone(left) }
    }

    @Test
    fun `pictures no workspace shows are deleted when the wallpapers are first read`() {
        cleanStore()
    }

    @Test
    fun `a picture is deleted once it is replaced, and the last one once the wallpaper is removed`() {
        val store = cleanStore()
        val first = picture("first.jpg")
        val second = picture("second.jpg")

        store.set("work", WorkspaceWallpaper("first.jpg"))
        store.set("work", WorkspaceWallpaper("second.jpg", intensity = 0.9f))

        awaitGone(first)
        assertTrue(second.exists())
        assertEquals(mapOf("work" to WorkspaceWallpaper("second.jpg", intensity = 0.9f)), store.wallpapers.value)

        store.set("work", null)

        awaitGone(second)
        assertEquals(emptyMap<String, WorkspaceWallpaper>(), store.wallpapers.value)
    }

    @Test
    fun `a picture picked and then given up is deleted`() {
        val store = cleanStore()
        val picked = picture("picked.jpg")

        store.discard("picked.jpg")

        awaitGone(picked)
    }

    @Test
    fun `a new workspace gets its wallpaper by the id adding it returns, and keeps it after a restart`() {
        val store = cleanStore()
        val repository = newRepository()
        picture("beach.jpg")

        val id = repository.addWorkspace("Holiday", containerId = null, icon = null, theme = null)
        store.set(id, WorkspaceWallpaper("beach.jpg", intensity = 0.8f))

        assertEquals(id, repository.state.value.activeWorkspaceId)
        assertEquals("Holiday", repository.state.value.workspaces.single { it.id == id }.name)
        assertEquals(mapOf(id to WorkspaceWallpaper("beach.jpg", intensity = 0.8f)), newStore().wallpapers.value)
    }

    @Test
    fun `wallpapers are read back as they were written`() {
        val wallpapers = mapOf(
            "work" to WorkspaceWallpaper(
                file = "a.jpg",
                intensity = 0.2f,
                colors = listOf(RED, GREEN, BLUE),
                light = 0.75f,
                dark = 0.0625f,
                lightLook = true,
            ),
            "home" to WorkspaceWallpaper(file = "b.jpg"),
        )

        assertEquals(wallpapers, readWallpapers(writeWallpapers(wallpapers)))
    }

    @Test
    fun `a wallpaper saved before wallpapers had a look shows in the dark one`() {
        val read = readWallpapers("""{"work":{"file":"a.jpg","intensity":0.6,"light":0.9,"dark":0.1}}""")

        assertFalse(read.getValue("work").lightLook)
    }

    @Test
    fun `only numbers are read as a wallpaper's colors, three at most`() {
        val read = readWallpapers("""{"work":{"file":"a.jpg","colors":[-1,"red",5,6,7]}}""")

        assertEquals(listOf(-1, 5, 6), read["work"]?.colors)
    }

    @Test
    fun `a wallpaper without a bare picture name is left out`() {
        val json = """{"work":{"file":"../shared_prefs/x.xml"},"home":{"file":"b.jpg"},"play":{"blur":0.5}}"""

        assertEquals(setOf("home"), readWallpapers(json).keys)
    }

    @Test
    fun `an intensity out of range is brought back in, and a missing one takes the default`() {
        val read = readWallpapers(
            """{"a":{"file":"a.jpg","intensity":3},"c":{"file":"c.jpg","intensity":-1},"b":{"file":"b.jpg"}}""",
        )

        assertEquals(WorkspaceWallpaper("a.jpg", intensity = 1f), read["a"])
        assertEquals(WorkspaceWallpaper("c.jpg", intensity = 0f), read["c"])
        assertEquals(WorkspaceWallpaper("b.jpg"), read["b"])
    }

    @Test
    fun `a wallpaper saved blurred shows the less, the more it was blurred`() {
        val read = readWallpapers("""{"work":{"file":"a.jpg","blur":0.75},"home":{"file":"b.jpg","blur":0}}""")

        assertEquals(0.25f, read["work"]?.intensity ?: 0f, 0.001f)
        assertEquals(1f, read["home"]?.intensity ?: 0f, 0.001f)
    }

    @Test
    fun `shades that are not numbers are left out`() {
        val read = readWallpapers("""{"work":{"file":"a.jpg","light":"bright","dark":0.5}}""")

        assertEquals(null, read["work"]?.light)
        assertEquals(0.5f, read["work"]?.dark ?: 0f, 0.001f)
    }

    @Test
    fun `nothing saved or broken data reads as no wallpapers`() {
        assertEquals(emptyMap<String, WorkspaceWallpaper>(), readWallpapers(null))
        assertEquals(emptyMap<String, WorkspaceWallpaper>(), readWallpapers("not json"))
    }

    @Test
    fun `a picture is read shrunk by the largest power of two that still covers the screen`() {
        assertEquals(1, coverSampleSize(4000, 3000, 720, 1600))
        assertEquals(4, coverSampleSize(6000, 8000, 720, 1600))
        assertEquals(1, coverSampleSize(500, 500, 720, 1600))
    }

    @Test
    fun `a picture is shrunk to just cover the screen, and a small one is never grown`() {
        assertEquals(2133 to 1600, coverSize(4000, 3000, 720, 1600))
        assertEquals(720 to 1600, coverSize(1440, 3200, 720, 1600))
        assertEquals(500 to 500, coverSize(500, 500, 720, 1600))
    }

    private fun pixels(colorAt: (x: Int, y: Int) -> Int) = IntArray(WIDTH * HEIGHT) { colorAt(it % WIDTH, it / WIDTH) }

    private fun thirds(y: Int) = if (y < 27) RED else if (y < 54) GREEN else BLUE

    @Test
    fun `a picture gives the colors along the theme's line, from the top start corner to the bottom end corner`() {
        val pixels = pixels { _, y -> thirds(y) }

        assertEquals(listOf(RED, GREEN, BLUE), gradientColorsOf(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun `the most common color of each part wins over a few other pixels`() {
        val pixels = pixels { x, y -> if ((x + y) % 10 == 0) WHITE else thirds(y) }

        assertEquals(listOf(RED, GREEN, BLUE), gradientColorsOf(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun `a vivid color covering a good part of the picture wins over a larger dull one`() {
        val pixels = pixels { x, y -> if ((x + y) % 10 < 3) RED else DARK }

        assertEquals(listOf(RED), gradientColorsOf(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun `a dull color still wins over a vivid one covering only a little of the picture`() {
        val pixels = pixels { x, y -> if ((x + y) % 10 == 0) RED else DARK }

        assertEquals(listOf(DARK), gradientColorsOf(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun `a color close to the one before it is left out, so a picture of fewer colors gives fewer`() {
        assertEquals(listOf(RED, BLUE), gradientColorsOf(pixels { _, y -> if (y < 40) RED else BLUE }, WIDTH, HEIGHT))
        assertEquals(listOf(RED), gradientColorsOf(pixels { _, y -> if (y < 40) RED else NEARLY_RED }, WIDTH, HEIGHT))
        assertEquals(listOf(GREEN), gradientColorsOf(pixels { _, _ -> GREEN }, WIDTH, HEIGHT))
    }

    @Test
    fun `shades of one color give their average`() {
        val pixels = pixels { x, _ -> if (x % 2 == 0) 0xFF102030.toInt() else 0xFF122232.toInt() }

        assertEquals(listOf(0xFF112131.toInt()), gradientColorsOf(pixels, WIDTH, HEIGHT))
    }

    @Test
    fun `a see-through picture gives no colors`() {
        assertEquals(emptyList<Int>(), gradientColorsOf(IntArray(WIDTH * HEIGHT), WIDTH, HEIGHT))
    }

    @Test
    fun `the shades of a picture are how light its lightest tenth is and how dark its darkest tenth is`() {
        val pixels = pixels { x, _ -> if (x < 4) WHITE else if (x < 32) GRAY else BLACK }
        val (light, dark) = checkNotNull(shadesOf(pixels))

        assertEquals(1f, light, 0.001f)
        assertEquals(0f, dark, 0.001f)
    }

    @Test
    fun `a picture of one color is as light as it is dark`() {
        val (light, dark) = checkNotNull(shadesOf(pixels { _, _ -> GRAY }))

        assertEquals(light, dark, 0.001f)
        assertEquals(0.2158f, light, 0.001f)
    }

    @Test
    fun `a see-through picture has no shades`() {
        assertEquals(null, shadesOf(IntArray(WIDTH * HEIGHT)))
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val WIDTH = 36
        const val HEIGHT = 80
        val RED = 0xFFE53935.toInt()
        val NEARLY_RED = 0xFFD32F2F.toInt()
        val GREEN = 0xFF43A047.toInt()
        val BLUE = 0xFF1E88E5.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        val GRAY = 0xFF808080.toInt()
        val DARK = 0xFF1E1E1E.toInt()
    }
}
