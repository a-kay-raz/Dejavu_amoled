/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.features

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.WordFittedText
import org.mozilla.fenix.dejavu.ui.popupGlow
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * A feature the tour shows: its title, where to find it, and its animation. The animations and the GIFs to share
 * them are made by scripts/dejavu-feature-gifs.py, which writes the same titles on the GIFs.
 *
 * @property title What the feature does, in a few words.
 * @property animation The phone showing the feature, an animated WebP.
 * @property where The steps to the feature's settings, or nothing when it has none. The settings gear in front stands
 *   for the first step, Settings, which is left out so long paths fit.
 * @property whereIcon The icon in front of [where].
 */
enum class DejavuFeature(
    @param:StringRes val title: Int,
    @param:RawRes val animation: Int,
    val where: List<Int> = emptyList(),
    @param:DrawableRes val whereIcon: Int = R.drawable.dejavu_ic_settings_24,
) {
    SLEEP(
        R.string.dejavu_feature_sleep,
        R.raw.dejavu_feature_sleep,
        listOf(R.string.dejavu_settings_tabs_workspaces, R.string.dejavu_settings_tab_sleep),
    ),
    THEMES(
        R.string.dejavu_feature_themes,
        R.raw.dejavu_feature_themes,
        listOf(R.string.dejavu_workspace_menu, R.string.dejavu_workspace_edit),
        iconsR.drawable.mozac_ic_ellipsis_vertical_24,
    ),
    SWIPE_MENU(
        R.string.dejavu_feature_swipe_menu,
        R.raw.dejavu_feature_swipe_menu,
        listOf(R.string.preferences_customize, R.string.dejavu_settings_more_menu),
    ),
    ACTIONS(
        R.string.dejavu_feature_actions,
        R.raw.dejavu_feature_actions,
        listOf(R.string.preferences_customize, R.string.dejavu_settings_tab_actions),
    ),
    ZEN_SYNC(
        R.string.dejavu_feature_zen_sync,
        R.raw.dejavu_feature_zen_sync,
        listOf(R.string.preferences_account_settings, R.string.dejavu_sync_source),
        R.drawable.dejavu_ic_profile_24,
    ),
    FOLDERS(R.string.dejavu_feature_folders, R.raw.dejavu_feature_folders),
    DRAG(R.string.dejavu_feature_drag, R.raw.dejavu_feature_drag),
    WORKSPACE_ORDER(R.string.dejavu_feature_workspace_order, R.raw.dejavu_feature_workspace_order),
    SPLIT_VIEW(R.string.dejavu_feature_split_view, R.raw.dejavu_feature_split_view),
    FIREFOX_SYNC(
        R.string.dejavu_feature_firefox_sync,
        R.raw.dejavu_feature_firefox_sync,
        listOf(R.string.preferences_account_settings, R.string.dejavu_sync_source),
        R.drawable.dejavu_ic_profile_24,
    ),
    TAB_ZOOM(R.string.dejavu_feature_tab_zoom, R.raw.dejavu_feature_tab_zoom),
    BAR_LAYOUTS(
        R.string.dejavu_feature_bar_layouts,
        R.raw.dejavu_feature_bar_layouts,
        listOf(R.string.preferences_customize, R.string.dejavu_settings_bars_2),
    ),
    WALLPAPER(
        R.string.dejavu_feature_wallpaper,
        R.raw.dejavu_feature_wallpaper,
        listOf(R.string.dejavu_workspace_menu, R.string.dejavu_workspace_edit),
        iconsR.drawable.mozac_ic_ellipsis_vertical_24,
    ),
    SAVE_PAGE(
        R.string.dejavu_feature_save_page,
        R.raw.dejavu_feature_save_page,
        listOf(R.string.preferences_customize, R.string.dejavu_settings_more_menu),
    ),
}

/**
 * What one version of Dejavu brought, a part of the tour with its chip at the top.
 *
 * @property version The version, like "2.0".
 * @property features The features it shows, in order.
 */
data class TourChapter(val version: String, val features: List<DejavuFeature>)

/** The tour of what each version of Dejavu brought, which only What's new in the settings shows. */
object FeatureTour {
    /** What each version brought, oldest first. A version with something to show adds its chapter at the end. */
    val Releases = listOf(
        TourChapter(
            version = "1.0",
            features = listOf(
                DejavuFeature.THEMES,
                DejavuFeature.SWIPE_MENU,
                DejavuFeature.ACTIONS,
                DejavuFeature.ZEN_SYNC,
                DejavuFeature.FOLDERS,
                DejavuFeature.DRAG,
                DejavuFeature.WORKSPACE_ORDER,
                DejavuFeature.SPLIT_VIEW,
            ),
        ),
        TourChapter(version = "1.6", features = listOf(DejavuFeature.SLEEP)),
        TourChapter(version = "2.0", features = listOf(DejavuFeature.FIREFOX_SYNC)),
        TourChapter(
            version = "3.0",
            features = listOf(
                DejavuFeature.TAB_ZOOM,
                DejavuFeature.BAR_LAYOUTS,
                DejavuFeature.WALLPAPER,
                DejavuFeature.SAVE_PAGE,
            ),
        ),
    )

    /** Compares versions like "2.0" and "1.10" part by part, a missing part counting as 0. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}

/**
 * Whether Dejavu was updated since its data began, rather than installed fresh. Clearing its data in the system's
 * settings begins it again, so the start after that is a fresh one too, although the system's install times tell an
 * update.
 */
fun Context.wasUpdated(): Boolean = DataStart.wasUpdated(this)

/** When Dejavu's data began, kept as the time of the app's last update then, see [wasUpdated]. */
private object DataStart {
    private const val PREFS_NAME = "dejavu_data_start"
    private const val KEY_UPDATE_TIME = "update_time"
    private const val NONE = -1L

    @Volatile
    private var updated: Boolean? = null

    fun wasUpdated(context: Context): Boolean = updated ?: synchronized(this) {
        updated ?: decide(context).also { updated = it }
    }

    private fun decide(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val began = prefs.getLong(KEY_UPDATE_TIME, NONE)
            when {
                began != NONE -> info.lastUpdateTime - began > UPDATE_MARGIN_MS
                // Data from a Dejavu older than this record, which the install times tell about.
                WorkspaceRepository.get(context).foundSavedState ->
                    info.lastUpdateTime - info.firstInstallTime > UPDATE_MARGIN_MS
                else -> {
                    prefs.edit { putLong(KEY_UPDATE_TIME, info.lastUpdateTime) }
                    false
                }
            }
        }
    }.getOrDefault(false)
}

/**
 * The tour, one feature a page: the title, where to find it, and the phone showing it. The chips at the top name the
 * versions of the [chapters] and go to them; it opens on chapter [startChapter]. Swiping or Next goes on,
 * from one chapter to the next; Skip goes to the next chapter, and on the last one it ends the tour with [onDone], like
 * Done.
 */
@Composable
fun FeatureTourCard(
    chapters: List<TourChapter>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    startChapter: Int = 0,
) {
    val pages = remember(chapters) { chapters.flatMapIndexed { index, chapter -> chapter.features.map { index to it } } }
    val starts = remember(chapters) { chapters.runningFold(0) { start, chapter -> start + chapter.features.size } }
    val pagerState = rememberPagerState(initialPage = starts.getOrElse(startChapter) { 0 }) { pages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex
    val chapter = pages.getOrNull(pagerState.currentPage)?.first ?: 0
    val animationHeight = (LocalConfiguration.current.screenHeightDp * ANIMATION_SHARE).dp.coerceAtMost(MAX_ANIMATION)
    fun goTo(page: Int) = scope.launch { pagerState.animateScrollToPage(page) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = MAX_WIDTH)
            .fillMaxWidth()
            .shadow(12.dp, DejavuPopup.DialogShape)
            .clip(DejavuPopup.DialogShape)
            .background(DejavuPopup.containerColor)
            .popupGlow()
            .border(1.dp, DejavuPopup.edgeColor, DejavuPopup.DialogShape)
            .padding(vertical = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                chapters.forEachIndexed { index, item ->
                    ChapterChip(
                        label = item.version,
                        selected = index == chapter,
                        onClick = { goTo(starts[index]) },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dejavu_features_page, pagerState.currentPage + 1, pages.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { page ->
            FeaturePage(
                feature = pages[page].second,
                playing = page == pagerState.currentPage,
                animationHeight = animationHeight,
            )
        }
        // Every page of the tour, the other chapters' too, so that a tour opened on one chapter shows there is more.
        PageDots(count = pages.size, current = pagerState.currentPage)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            if (!isLast) {
                DejavuDialogButton(
                    text = stringResource(R.string.dejavu_features_skip),
                    onClick = { if (chapter < chapters.lastIndex) goTo(starts[chapter + 1]) else onDone() },
                )
            }
            DejavuDialogButton(
                text = stringResource(if (isLast) R.string.dejavu_done else R.string.dejavu_features_next),
                style = DejavuButtonStyle.Primary,
                onClick = { if (isLast) onDone() else goTo(pagerState.currentPage + 1) },
            )
        }
    }
}

/**
 * The chip of a chapter of the tour, its version, filled for the chapter shown, which the row of chips
 * scrolls to when they do not all fit.
 */
@Composable
private fun ChapterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun FeaturePage(feature: DejavuFeature, playing: Boolean, animationHeight: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        val titleStyle = MaterialTheme.typography.titleLarge
        Text(
            text = stringResource(feature.title),
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = titleStyle.fontSize * MIN_TEXT_SCALE,
                maxFontSize = titleStyle.fontSize,
            ),
        )
        // Pages without settings keep the same room, so the phone stays in place while swiping.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(WHERE_HEIGHT).padding(top = 8.dp)) {
            if (feature.where.isNotEmpty()) WherePill(feature)
        }
        FeatureAnimation(
            animation = feature.animation,
            playing = playing,
            modifier = Modifier.padding(top = 8.dp).height(animationHeight).aspectRatio(ANIMATION_RATIO),
        )
    }
}

/** Where to find the feature, like "Settings › Sleeping tabs". */
@Composable
private fun WherePill(feature: DejavuFeature) {
    val separator = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "  ‹  " else "  ›  "
    val steps = feature.where.map { stringResource(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(feature.whereIcon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        WordFittedText(
            text = steps.joinToString(separator),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 14.dp),
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 8.dp else 6.dp)
                    .align(Alignment.CenterVertically)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DOT_ALPHA)
                        },
                    ),
            )
        }
    }
}

/**
 * Plays [animation] while [playing]. Android 9 and newer play animated WebP; older versions, and any with animations
 * turned off, show its first frame.
 * The title above already says what it shows, so screen readers skip it.
 */
@Composable
private fun FeatureAnimation(@RawRes animation: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, animation) {
        value = withContext(Dispatchers.IO) { decodeAnimation(context, animation) }
    }
    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            (drawable as? Animatable)?.let { if (playing) it.start() else it.stop() }
        },
        modifier = modifier,
        // A page swiped away lets go of its animation and the frames it decoded.
        onRelease = { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        },
    )
}

private fun decodeAnimation(context: Context, @RawRes animation: Int): Drawable? = runCatching {
    // With animations turned off in the system settings, the first frame stands still instead, as WebP ignores them.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ValueAnimator.areAnimatorsEnabled()) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, animation)).also {
            (it as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    } else {
        context.resources.openRawResource(animation).use { BitmapFactory.decodeStream(it) }
            ?.let { BitmapDrawable(context.resources, it) }
    }
}.getOrNull()

/**
 * What's new, opened from the settings: the whole tour, over the settings, in the colors of the workspace shown on the
 * home screen.
 */
class DejavuFeatureTourFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    val repository = remember { workspaceRepository() }
                    val workspaces by repository.state.collectAsState()
                    val popupTheme = workspacePopupTheme(workspaces.activeWorkspaceId)
                    CompositionLocalProvider(LocalPopupTheme provides popupTheme) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            // Every chapter, opened on what the newest version brought.
                            val chapters = FeatureTour.Releases
                            FeatureTourCard(chapters = chapters, onDone = { dismiss() }, startChapter = chapters.lastIndex)
                        }
                    }
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    companion object {
        const val TAG = "DejavuFeatureTour"
    }
}

/** The size of the animations, a phone with its frame and shadow. */
private const val ANIMATION_RATIO = 488f / 921f
private const val ANIMATION_SHARE = 0.48f
private const val DOT_ALPHA = 0.3f
private val MAX_ANIMATION = 440.dp
private val MAX_WIDTH = 480.dp
private val WHERE_HEIGHT = 56.dp

// How small a title gets before it is cut off, as a share of its size.
private const val MIN_TEXT_SCALE = 0.75f
private const val UPDATE_MARGIN_MS = 60_000L
