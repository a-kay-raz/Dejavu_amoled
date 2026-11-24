/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.browser.SplitPanes
import org.mozilla.fenix.snackbar.FenixSnackbarDelegate

/**
 * Saves the whole page of a tab, from top to bottom, as one picture in Pictures/Dejavu, and says so with a button that
 * opens it. Firefox draws a long page smaller instead of into a picture too big for memory, see GeckoDisplay.
 */
internal object FullPageScreenshot {
    private const val FOLDER = "Dejavu"
    private const val PERMISSION_KEY = "dejavu_screenshot_storage"

    // Only used on the main thread: while a page is being pictured, another tap does nothing.
    private var busy = false

    /** Whether a tab may be pictured: a private one only where screenshots are allowed in private browsing. */
    fun isAllowed(private: Boolean, secureModeOverridden: Boolean): Boolean = !private || secureModeOverridden

    /** Pictures the page of [tab], asked for from [context], a context of the browser's activity. */
    fun take(context: Context, components: Components, tab: TabSessionState) {
        val activity = context.findActivity() ?: return
        if (busy || !isAllowed(tab.content.private, components.settings.shouldSecureModeBeOverridden)) return
        // Before Android 10 a picture only goes into Pictures with the permission to store files.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !canStoreFiles(activity)) {
            askToStoreFiles(activity) { take(activity, components, tab) }
            return
        }
        val engineView = SplitPanes.active?.engineViewOf(tab.id)
            ?: return tell(activity, R.string.dejavu_screenshot_failed, Toast.LENGTH_LONG)
        busy = true
        tell(activity, R.string.dejavu_screenshot_saving, Toast.LENGTH_SHORT)
        engineView.captureFullPage { picture ->
            if (picture == null) {
                busy = false
                tell(activity, R.string.dejavu_screenshot_failed, Toast.LENGTH_LONG)
                return@captureFullPage
            }
            components.applicationScope.launch(Dispatchers.Main) {
                val uri = withContext(Dispatchers.IO) {
                    try {
                        save(activity.applicationContext, picture)
                    } finally {
                        picture.recycle()
                    }
                }
                busy = false
                if (uri == null) {
                    tell(activity, R.string.dejavu_screenshot_save_failed, Toast.LENGTH_LONG)
                } else {
                    showSaved(activity, uri)
                }
            }
        }
    }

    private fun tell(context: Context, @StringRes text: Int, duration: Int) =
        Toast.makeText(context.applicationContext, text, duration).show()

    private fun canStoreFiles(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks for the permission to store files, and runs [then] once it is given. */
    private fun askToStoreFiles(activity: ComponentActivity, then: () -> Unit) {
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            PERMISSION_KEY,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            launcher.unregister()
            if (granted) then() else tell(activity, R.string.dejavu_screenshot_permission, Toast.LENGTH_LONG)
        }
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** Writes [picture] into Pictures/Dejavu as a PNG file, or returns null when it cannot. */
    @Suppress("DEPRECATION")
    private fun save(context: Context, picture: Bitmap): Uri? {
        val name = "Dejavu-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                // Hidden from other apps until it is written, so a gallery never lists an empty file.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
                runCatching { folder.mkdirs() }
                put(MediaStore.Images.Media.DATA, File(folder, name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return null
        val saved = runCatching {
            val written = resolver.openOutputStream(uri)?.use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: false
            if (written && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            written
        }.getOrDefault(false)
        if (!saved) runCatching { resolver.delete(uri, null, null) }
        return uri.takeIf { saved }
    }

    /**
     * Says where the picture went, with a button that opens it, on the page it was taken from; once that page is no
     * longer shown, for example after a rotation, only says where it went.
     */
    private fun showSaved(activity: Activity, uri: Uri) {
        val text = activity.getString(R.string.dejavu_screenshot_saved, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
        val container = activity.takeUnless { it.isFinishing || it.isDestroyed }
            ?.findViewById<View>(R.id.dynamicSnackbarContainer)
            ?.takeIf { it.isShown }
        if (container == null) {
            Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
            return
        }
        FenixSnackbarDelegate(container).show(
            text = text,
            action = activity.getString(R.string.dejavu_open),
            listener = { open(activity, uri) },
        )
    }

    private fun open(activity: Activity, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(view) }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
