/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A picture a workspace shows on the home screen in place of its theme's gradient, under a veil of its own colors that
 * keeps the text over it easy to read.
 *
 * @param file The picture's name in the folder of [WorkspaceWallpapers].
 * @param intensity How strongly the picture shows through its veil, from the most veiled at 0 to the clearest at 1.
 * @param colors The colors of the gradient that sums up the picture, which the workspace takes as its theme so that
 * Zen, which syncs themes but not pictures, shows the same colors. Empty until they are taken from the picture.
 * @param light How light the lightest parts of the picture are, as a relative luminance: a tenth of it is lighter.
 * @param dark How dark the darkest parts of the picture are: a tenth of it is darker. Both are `null` until they are
 * taken from the picture.
 * @param lightLook Whether it shows in its light look, under a pale veil with dark text, as picked among the light
 * wallpapers; otherwise in its dark look, under a deep veil with light text. Either way it looks the same in the
 * app's light and dark themes.
 */
data class WorkspaceWallpaper(
    val file: String,
    val intensity: Float = DEFAULT_INTENSITY,
    val colors: List<Int> = emptyList(),
    val light: Float? = null,
    val dark: Float? = null,
    val lightLook: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTENSITY = 0.6f
    }
}

/**
 * Keeps the [WorkspaceWallpaper] of each workspace that has one, by workspace id, with their pictures in the app's
 * files. Unlike the rest of a workspace, wallpapers stay on this device: they are not synced.
 */
class WorkspaceWallpapers private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _wallpapers = MutableStateFlow(readWallpapers(prefs.getString(KEY_WALLPAPERS, null)))

    /** The wallpapers, by the id of their workspace. */
    val wallpapers: StateFlow<Map<String, WorkspaceWallpaper>> = _wallpapers.asStateFlow()

    // How many times each workspace was given a wallpaper or had it taken away, so one drawn meanwhile can tell it came
    // too late.
    private val changes = ConcurrentHashMap<String, Int>()

    private val tidied = io.launch {
        deleteUnusedPictures()
        addMissingLooks()
    }

    /** Gives workspace [workspaceId] [wallpaper], or no wallpaper. A picture no workspace shows anymore is deleted. */
    fun set(workspaceId: String, wallpaper: WorkspaceWallpaper?) {
        changes.merge(workspaceId, 1, Int::plus)
        var replaced: String? = null
        var changed: Map<String, WorkspaceWallpaper>? = null
        _wallpapers.update { old ->
            replaced = old[workspaceId]?.file
            val new = if (wallpaper == null) old - workspaceId else old + (workspaceId to wallpaper)
            changed = new.takeIf { new != old }
            new
        }
        changed?.let { prefs.edit { putString(KEY_WALLPAPERS, writeWallpapers(it)) } }
        replaced?.takeIf { it != wallpaper?.file }?.let(::discard)
    }

    /**
     * Gives each workspace of [arts], by its id, the wallpaper a fresh Dejavu starts it with, drawn for a screen of
     * [screenWidth] by [screenHeight] pixels, unless it has a wallpaper, or one is given or taken away before it is
     * drawn.
     */
    fun giveFirstWallpapers(arts: Map<String, WallpaperArt>, screenWidth: Int, screenHeight: Int) {
        val due = arts.filterKeys { it !in _wallpapers.value }
        if (due.isEmpty()) return
        val before = due.keys.associateWith { changes[it] ?: 0 }
        io.launch {
            tidied.join()
            due.forEach { (workspaceId, art) ->
                val wallpaper = importArt(art, screenWidth, screenHeight) ?: return@forEach
                if ((changes[workspaceId] ?: 0) == before[workspaceId] && workspaceId !in _wallpapers.value) {
                    set(workspaceId, wallpaper)
                } else {
                    pictureFile(wallpaper.file).delete()
                }
            }
        }
    }

    /** Deletes picture [file] unless a workspace shows it, like one picked for a wallpaper that was not saved. */
    fun discard(file: String) {
        io.launch {
            if (_wallpapers.value.values.none { it.file == file }) pictureFile(file).delete()
        }
    }

    /** Where picture [file] is kept. Finding it can touch the disk, so it is not for the main thread. */
    fun pictureFile(file: String): File = File(File(appContext.filesDir, FOLDER), file)

    /**
     * Copies the picture at [uri] into the app's files, upright and just big enough to cover a screen of
     * [screenWidth] by [screenHeight] pixels, and returns a wallpaper of it with the colors and the shades of the part
     * the screen shows; `null` when it cannot be read.
     */
    suspend fun importPicture(uri: Uri, screenWidth: Int, screenHeight: Int): WorkspaceWallpaper? =
        withContext(Dispatchers.IO) {
            runCatching {
                val picture = decodeUpright(uri, screenWidth, screenHeight) ?: return@runCatching null
                keep(picture, "${UUID.randomUUID()}.jpg", screenWidth, screenHeight)
            }.getOrNull()
        }

    /**
     * Keeps a picture of [art] drawn for a screen of [screenWidth] by [screenHeight] pixels, and returns a wallpaper of
     * it; `null` when it cannot be kept.
     */
    suspend fun importArt(art: WallpaperArt, screenWidth: Int, screenHeight: Int): WorkspaceWallpaper? =
        withContext(Dispatchers.IO) {
            runCatching {
                val picture = art.draw(screenWidth, screenHeight)
                val name = "${WallpaperArt.FILE_PREFIX}${art.key}-${UUID.randomUUID()}.jpg"
                keep(picture, name, screenWidth, screenHeight)
            }.getOrNull()
        }

    /** Writes [picture] as [name], recycles it, and returns a wallpaper of it, or `null` when it was not written. */
    private fun keep(picture: Bitmap, name: String, screenWidth: Int, screenHeight: Int): WorkspaceWallpaper? {
        val look = picture.lookOnScreen(screenWidth, screenHeight)
        val file = pictureFile(name)
        file.parentFile?.mkdirs()
        val written = file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        picture.recycle()
        if (!written) file.delete()
        return look.applyTo(WorkspaceWallpaper(file = name)).takeIf { written }
    }

    private fun decodeUpright(uri: Uri, screenWidth: Int, screenHeight: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val turned = orientation in TURNED_ORIENTATIONS
        val width = if (turned) bounds.outHeight else bounds.outWidth
        val height = if (turned) bounds.outWidth else bounds.outHeight
        val options = BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(width, height, screenWidth, screenHeight)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val upright = decoded.turnedUpright(orientation)
        val (coverWidth, coverHeight) = coverSize(upright.width, upright.height, screenWidth, screenHeight)
        if (coverWidth >= upright.width) return upright
        return upright.scale(coverWidth, coverHeight).also { if (it !== upright) upright.recycle() }
    }

    private fun deleteUnusedPictures() {
        val used = _wallpapers.value.values.map { it.file }.toSet()
        File(appContext.filesDir, FOLDER).listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    /**
     * Takes the colors and the shades of the pictures of wallpapers saved without them, before wallpapers had colors,
     * and before they had shades.
     */
    private fun addMissingLooks() {
        val screen = appContext.resources.displayMetrics
        val missing = _wallpapers.value.values.filter { it.colors.isEmpty() || it.light == null }
        val found = missing.mapNotNull { wallpaper ->
            val options = BitmapFactory.Options().apply { inSampleSize = COLOR_SAMPLE_SIZE }
            val picture = runCatching { BitmapFactory.decodeFile(pictureFile(wallpaper.file).path, options) }
                .getOrNull() ?: return@mapNotNull null
            val look = picture.lookOnScreen(screen.widthPixels, screen.heightPixels)
            picture.recycle()
            wallpaper.file to look
        }.toMap()
        if (found.isEmpty()) return
        var changed: Map<String, WorkspaceWallpaper>? = null
        _wallpapers.update { old ->
            val new = old.mapValues { (_, wallpaper) -> found[wallpaper.file]?.applyTo(wallpaper) ?: wallpaper }
            changed = new.takeIf { new != old }
            new
        }
        changed?.let { prefs.edit { putString(KEY_WALLPAPERS, writeWallpapers(it)) } }
    }

    companion object {
        private const val PREFS_NAME = "dejavu_wallpapers"
        private const val KEY_WALLPAPERS = "wallpapers"
        private const val FOLDER = "dejavu_wallpapers"
        private const val JPEG_QUALITY = 90

        @Volatile
        private var instance: WorkspaceWallpapers? = null

        /** Returns the process wide [WorkspaceWallpapers]. Reads from disk on first use. */
        fun get(context: Context): WorkspaceWallpapers =
            instance ?: synchronized(this) {
                instance ?: WorkspaceWallpapers(context.applicationContext).also { instance = it }
            }
    }
}

private const val KEY_FILE = "file"
private const val KEY_INTENSITY = "intensity"
private const val KEY_COLORS = "colors"
private const val KEY_LIGHT = "light"
private const val KEY_DARK = "dark"
private const val KEY_LIGHT_LOOK = "lightLook"

// Before wallpapers had a veil, they were blurred, as much as this said: the more blurred, the less they showed.
private const val KEY_BLUR = "blur"

/** As many colors as a theme synced with Zen can have. */
private const val GRADIENT_COLORS = 3
private const val SAMPLE_COLUMNS = 36
private const val CHANNEL_BITS = 3
private const val CHANNEL_MAX = 0xFF
private const val MIN_ALPHA = 0x80
private const val MIN_COLOR_DISTANCE = 40.0

/** How much more a vivid color counts than a gray one covering as much of the picture. */
private const val VIVID_WEIGHT = 3f

/** The share of a picture lighter than its light end, and darker than its dark end. */
private const val SHADE_SHARE = 0.1f
private const val SRGB_KNEE = 0.04045f
private const val SRGB_SLOPE = 12.92f
private const val SRGB_OFFSET = 0.055f
private const val SRGB_GAMMA = 2.4f
private const val LUMA_RED = 0.2126f
private const val LUMA_GREEN = 0.7152f
private const val LUMA_BLUE = 0.0722f
private const val COLOR_SAMPLE_SIZE = 4
private const val QUARTER_TURN = 90f

private val TURNED_ORIENTATIONS = setOf(
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE,
)

/** Reads wallpapers written by [writeWallpapers]; whatever cannot be read is left out. */
internal fun readWallpapers(json: String?): Map<String, WorkspaceWallpaper> {
    val root = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
    return root.keys().asSequence().mapNotNull { workspaceId ->
        val item = root.optJSONObject(workspaceId) ?: return@mapNotNull null
        // Only a bare file name, so a wallpaper can never point out of its folder.
        val file = item.optString(KEY_FILE).takeIf { it.isNotEmpty() && File(it).name == it } ?: return@mapNotNull null
        val colors = item.optJSONArray(KEY_COLORS)
        val intensity = when {
            item.has(KEY_INTENSITY) -> item.optDouble(KEY_INTENSITY).toFloat()
            item.has(KEY_BLUR) -> 1f - item.optDouble(KEY_BLUR).toFloat()
            else -> WorkspaceWallpaper.DEFAULT_INTENSITY
        }
        workspaceId to WorkspaceWallpaper(
            file = file,
            intensity = intensity.takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: WorkspaceWallpaper.DEFAULT_INTENSITY,
            colors = (0 until (colors?.length() ?: 0))
                .mapNotNull { (colors?.opt(it) as? Number)?.toInt() }
                .take(GRADIENT_COLORS),
            light = item.optShade(KEY_LIGHT),
            dark = item.optShade(KEY_DARK),
            // Wallpapers kept before they had a look of their own show in the dark one.
            lightLook = item.optBoolean(KEY_LIGHT_LOOK),
        )
    }.toMap()
}

private fun JSONObject.optShade(key: String): Float? =
    (opt(key) as? Number)?.toFloat()?.takeUnless { it.isNaN() }?.coerceIn(0f, 1f)

internal fun writeWallpapers(wallpapers: Map<String, WorkspaceWallpaper>): String = JSONObject().apply {
    wallpapers.forEach { (workspaceId, wallpaper) ->
        put(
            workspaceId,
            JSONObject()
                .put(KEY_FILE, wallpaper.file)
                .put(KEY_INTENSITY, wallpaper.intensity.toDouble())
                .put(KEY_COLORS, JSONArray(wallpaper.colors))
                .apply {
                    wallpaper.light?.let { put(KEY_LIGHT, it.toDouble()) }
                    wallpaper.dark?.let { put(KEY_DARK, it.toDouble()) }
                    if (wallpaper.lightLook) put(KEY_LIGHT_LOOK, true)
                },
        )
    }
}.toString()

/**
 * The largest power of two a picture of [width] by [height] pixels can be shrunk by while reading it, so that it
 * still covers [targetWidth] by [targetHeight] pixels.
 */
internal fun coverSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    val room = min(width.toFloat() / targetWidth.coerceAtLeast(1), height.toFloat() / targetHeight.coerceAtLeast(1))
    var sampleSize = 1
    while (sampleSize * 2 <= room) sampleSize *= 2
    return sampleSize
}

/** The size of a picture of [width] by [height] pixels shrunk to just cover [targetWidth] by [targetHeight] pixels. */
internal fun coverSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> {
    val scale = max(targetWidth.toFloat() / width, targetHeight.toFloat() / height).coerceAtMost(1f)
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * Up to three colors that sum up a picture the way a workspace theme's gradient runs, from the top start corner of
 * the screen to its bottom end corner: the main color of each third of the picture along that line, in that order.
 * The main color is the most common one, a vivid color counting up to [VIVID_WEIGHT] times more than a gray one, so
 * that a red covering a good part of a dark picture is not lost to the dark. A color close to the one kept before it
 * is left out, so a picture of few colors gives fewer.
 *
 * @param pixels The part of the picture the screen shows, a few thousand ARGB pixels row by row, [width] to a row.
 */
internal fun gradientColorsOf(pixels: IntArray, width: Int, height: Int): List<Int> {
    val bins = 1 shl (3 * CHANNEL_BITS)
    val counts = Array(GRADIENT_COLORS) { IntArray(bins) }
    val sums = Array(GRADIENT_COLORS) { LongArray(bins * 3) }
    val lineLength = width.toFloat() * width + height.toFloat() * height
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = pixels[y * width + x]
            if (color ushr 24 < MIN_ALPHA) continue
            val along = ((x + 0.5f) * width + (y + 0.5f) * height) / lineLength
            val third = (along * GRADIENT_COLORS).toInt().coerceIn(0, GRADIENT_COLORS - 1)
            val red = (color shr 16) and CHANNEL_MAX
            val green = (color shr 8) and CHANNEL_MAX
            val blue = color and CHANNEL_MAX
            val shift = 8 - CHANNEL_BITS
            val bin = ((red shr shift) shl (2 * CHANNEL_BITS)) or
                ((green shr shift) shl CHANNEL_BITS) or
                (blue shr shift)
            counts[third][bin]++
            sums[third][bin * 3] += red.toLong()
            sums[third][bin * 3 + 1] += green.toLong()
            sums[third][bin * 3 + 2] += blue.toLong()
        }
    }
    val colors = mutableListOf<Int>()
    for (third in 0 until GRADIENT_COLORS) {
        fun average(bin: Int, channel: Int): Int {
            val count = counts[third][bin]
            return ((sums[third][bin * 3 + channel] + count / 2) / count).toInt()
        }
        fun weight(bin: Int): Float {
            val count = counts[third][bin].takeIf { it > 0 } ?: return 0f
            val channels = (0 until 3).map { average(bin, it) }
            val chroma = (channels.max() - channels.min()).toFloat() / CHANNEL_MAX
            return count * (1 + VIVID_WEIGHT * chroma)
        }
        val bin = counts[third].indices.maxBy(::weight)
        if (counts[third][bin] == 0) continue
        val color = opaque(red = average(bin, 0), green = average(bin, 1), blue = average(bin, 2))
        if (colors.lastOrNull()?.let { distance(it, color) < MIN_COLOR_DISTANCE } != true) colors += color
    }
    return colors
}

/** How a picture looks on the screen: the [colors] of its gradient, and how [light] and [dark] it is at its ends. */
private class PictureLook(val colors: List<Int>, val light: Float?, val dark: Float?) {
    /** [wallpaper] with this look, keeping colors and shades it had when none were found. */
    fun applyTo(wallpaper: WorkspaceWallpaper) = wallpaper.copy(
        colors = wallpaper.colors.ifEmpty { colors },
        light = light ?: wallpaper.light,
        dark = dark ?: wallpaper.dark,
    )
}

/** How the part of this picture that covers a screen of [screenWidth] by [screenHeight] pixels looks. */
private fun Bitmap.lookOnScreen(screenWidth: Int, screenHeight: Int): PictureLook {
    val scale = max(screenWidth.toFloat() / width, screenHeight.toFloat() / height)
    val shownWidth = screenWidth / scale
    val shownHeight = screenHeight / scale
    val left = (width - shownWidth) / 2
    val top = (height - shownHeight) / 2
    val rows = (SAMPLE_COLUMNS * screenHeight / screenWidth.coerceAtLeast(1)).coerceAtLeast(1)
    val pixels = IntArray(SAMPLE_COLUMNS * rows) { index ->
        val x = left + (index % SAMPLE_COLUMNS + 0.5f) * shownWidth / SAMPLE_COLUMNS
        val y = top + (index / SAMPLE_COLUMNS + 0.5f) * shownHeight / rows
        getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))
    }
    val shades = shadesOf(pixels)
    return PictureLook(gradientColorsOf(pixels, SAMPLE_COLUMNS, rows), shades?.first, shades?.second)
}

/**
 * A wallpaper of this picture in its [lightLook] or its dark one, with the colors and the shades a screen of the
 * picture's size shows, for a picture that is not kept, like the small one a choice of wallpaper shows.
 */
internal fun Bitmap.previewWallpaper(lightLook: Boolean): WorkspaceWallpaper =
    lookOnScreen(width, height).applyTo(WorkspaceWallpaper(file = "", lightLook = lightLook))

/**
 * How light the lightest tenth of [pixels] is and how dark the darkest tenth is, as relative luminances; `null` for a
 * see-through picture.
 */
internal fun shadesOf(pixels: IntArray): Pair<Float, Float>? {
    val luminances = pixels.filter { it ushr 24 >= MIN_ALPHA }.map(::luminanceOf).sorted()
    if (luminances.isEmpty()) return null
    val last = luminances.size - 1
    return luminances[(last * (1f - SHADE_SHARE)).roundToInt()] to luminances[(last * SHADE_SHARE).roundToInt()]
}

/** The relative luminance of the color [argb], from 0 for black to 1 for white. */
private fun luminanceOf(argb: Int): Float {
    fun linear(channel: Int): Float {
        val value = channel / CHANNEL_MAX.toFloat()
        if (value <= SRGB_KNEE) return value / SRGB_SLOPE
        return ((value + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
    }
    return LUMA_RED * linear((argb shr 16) and CHANNEL_MAX) +
        LUMA_GREEN * linear((argb shr 8) and CHANNEL_MAX) +
        LUMA_BLUE * linear(argb and CHANNEL_MAX)
}

private fun opaque(red: Int, green: Int, blue: Int): Int =
    (CHANNEL_MAX shl 24) or (red shl 16) or (green shl 8) or blue

private fun distance(first: Int, second: Int): Double {
    val red = ((first shr 16) and CHANNEL_MAX) - ((second shr 16) and CHANNEL_MAX)
    val green = ((first shr 8) and CHANNEL_MAX) - ((second shr 8) and CHANNEL_MAX)
    val blue = (first and CHANNEL_MAX) - (second and CHANNEL_MAX)
    return sqrt((red * red + green * green + blue * blue).toDouble())
}

/** Turns a picture read without its EXIF orientation the way the camera meant it. */
private fun Bitmap.turnedUpright(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER_TURN)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(2 * QUARTER_TURN)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(3 * QUARTER_TURN)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(QUARTER_TURN)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(3 * QUARTER_TURN)
            matrix.preScale(-1f, 1f)
        }
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { if (it !== this) recycle() }
}
