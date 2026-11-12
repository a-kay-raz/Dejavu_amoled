/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.welcome

import android.os.StrictMode
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.home.SystemBarIconsOver
import org.mozilla.fenix.dejavu.home.WallpaperImage
import org.mozilla.fenix.dejavu.home.WallpaperTheme
import org.mozilla.fenix.dejavu.home.drawVeil
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.rememberGrainBrush
import org.mozilla.fenix.dejavu.home.veilOf
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.glass
import org.mozilla.fenix.dejavu.ui.popupThemeOf
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers

/** The welcome pages, in the order they show. */
internal enum class WelcomePage { INTRO, SEARCH, LOOK, BARS, THEME, ADS, DEFAULT_BROWSER, SYNC }

/**
 * The welcome pages, on [page]: the progress and Skip above, Back and Next below. Behind them is the look of the
 * workspace shown, so a wallpaper or colors picked on the way show at once. A page moving on sinks away as the next
 * one comes up from behind it; going back, the other way round. [onDone] leaves them, for the sign-in when asked.
 */
@Composable
internal fun WelcomeFlow(page: Int, onPage: (Int) -> Unit, onDone: (signIn: Boolean) -> Unit) {
    val context = LocalContext.current
    val fenix = components
    val repository = remember {
        fenix.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { WorkspaceRepository.get(context) }
    }
    val wallpaperStore = remember {
        fenix.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { WorkspaceWallpapers.get(context) }
    }
    val state by repository.state.collectAsState()
    val workspace = state.activeWorkspace
    val look = remember(workspace?.id) {
        workspace?.let {
            WelcomeLook(repository, wallpaperStore, it.id, it.theme, wallpaperStore.wallpapers.value[it.id])
        }
    }
    if (look != null) {
        // The wallpaper a fresh Dejavu starts with may be drawn after the pages opened.
        val stored by wallpaperStore.wallpapers.collectAsState()
        val storedWallpaper = workspace?.id?.let { stored[it] }
        LaunchedEffect(look, storedWallpaper) { look.follow(storedWallpaper) }
        // Kept a moment after the last change, so dragging a slider saves once, and when the pages close.
        LaunchedEffect(look, look.theme, look.wallpaper) {
            delay(SAVE_DELAY_MS)
            look.save()
        }
        DisposableEffect(look) { onDispose { look.save() } }
    }

    val pages = WelcomePage.entries
    val shown = page.coerceIn(pages.indices)
    val isLast = shown == pages.lastIndex
    val next = { if (isLast) onDone(false) else onPage(shown + 1) }

    // The pages show in the look of the wallpaper picked, as the home screen does, whatever the app's theme.
    SystemBarIconsOver(look?.wallpaper)
    WallpaperTheme(look?.wallpaper) {
        Box(modifier = Modifier.fillMaxSize()) {
            WelcomeBackdrop(theme = look?.theme ?: workspace?.theme, wallpaper = look?.wallpaper)
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                ) {
                    PageProgress(count = pages.size, current = shown, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onDone(false) },
                        enabled = !isLast,
                        modifier = Modifier.alpha(if (isLast) 0f else 1f),
                    ) {
                        Text(text = stringResource(R.string.dejavu_features_skip))
                    }
                }
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val enter = fadeIn(tween(ENTER_MS, delayMillis = ENTER_DELAY_MS)) +
                            scaleIn(tween(ENTER_MS, easing = FastOutSlowInEasing), if (forward) BEHIND else IN_FRONT)
                        val exit = fadeOut(tween(EXIT_MS)) +
                            scaleOut(tween(EXIT_MS, easing = FastOutSlowInEasing), if (forward) IN_FRONT else BEHIND)
                        enter togetherWith exit
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "welcomePage",
                ) { index ->
                    Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = MAX_WIDTH)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = PAGE_MARGIN, vertical = 12.dp),
                        ) {
                            // Dialogs opened from the pages take on the colors of the wallpaper picked.
                            CompositionLocalProvider(LocalPopupTheme provides popupThemeOf(look?.wallpaper)) {
                                WelcomePageContent(page = pages[index], look = look, onSignIn = { onDone(true) })
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    if (shown > 0) {
                        DejavuDialogButton(
                            text = stringResource(R.string.dejavu_back),
                            onClick = { onPage(shown - 1) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    DejavuDialogButton(
                        text = stringResource(
                            when {
                                shown == 0 -> R.string.dejavu_welcome_start
                                isLast -> R.string.dejavu_welcome_done
                                else -> R.string.dejavu_features_next
                            },
                        ),
                        onClick = next,
                        style = DejavuButtonStyle.Primary,
                    )
                }
            }
        }
    }
}

/**
 * The look the welcome pages give the workspace shown, kept for it by [save]: its [theme], and its [wallpaper], which
 * gives the theme its colors, as the workspace dialog does.
 */
@Stable
internal class WelcomeLook(
    private val repository: WorkspaceRepository,
    private val wallpapers: WorkspaceWallpapers,
    private val workspaceId: String,
    theme: WorkspaceTheme?,
    wallpaper: WorkspaceWallpaper?,
) {
    var theme by mutableStateOf(theme)
        private set
    var wallpaper by mutableStateOf(wallpaper)
        private set
    private var savedTheme = theme
    private var savedWallpaper = wallpaper

    /** Shows [picked], or no wallpaper, which leaves the colors the workspace has. */
    fun showWallpaper(picked: WorkspaceWallpaper?) {
        // A picture picked before this one, and not kept, is deleted.
        wallpaper?.file?.takeIf { it != picked?.file && it != savedWallpaper?.file }?.let(wallpapers::discard)
        wallpaper = picked
        picked?.colors?.takeIf { it.isNotEmpty() }?.let { colors ->
            theme = WorkspaceTheme(
                colors = colors,
                opacity = theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                texture = theme?.texture ?: 0f,
            )
        }
    }

    /** Shows the colors of [picked], or none, without a wallpaper. */
    fun showTheme(picked: WorkspaceTheme?) {
        if (wallpaper != null) showWallpaper(null)
        theme = picked
    }

    fun setIntensity(intensity: Float) {
        wallpaper = wallpaper?.copy(intensity = intensity)
    }

    /** Shows [stored], the wallpaper given to the workspace meanwhile, unless another one was picked here. */
    fun follow(stored: WorkspaceWallpaper?) {
        if (wallpaper != savedWallpaper || stored == savedWallpaper) return
        wallpaper = stored
        savedWallpaper = stored
    }

    /** Keeps the look shown for the workspace, if it changed since it was last kept. */
    fun save() {
        if (theme != savedTheme) {
            repository.state.value.workspaces.firstOrNull { it.id == workspaceId }?.let {
                repository.updateWorkspace(it.id, it.name, it.containerId, it.icon, theme)
            }
            savedTheme = theme
        }
        if (wallpaper != savedWallpaper) {
            wallpapers.set(workspaceId, wallpaper)
            savedWallpaper = wallpaper
        }
    }
}

/**
 * The look of the workspace behind the pages, as the home screen shows it: the wallpaper under its veil, or the
 * theme's colors. A new picture or new colors fade in over the old ones.
 */
@Composable
private fun WelcomeBackdrop(theme: WorkspaceTheme?, wallpaper: WorkspaceWallpaper?) {
    val surface = MaterialTheme.colorScheme.surface
    val text = MaterialTheme.colorScheme.onSurface
    val grain = rememberGrainBrush()
    AnimatedContent(
        targetState = theme to wallpaper,
        contentKey = { (shownTheme, shownWallpaper) -> shownWallpaper?.file ?: shownTheme?.colors },
        transitionSpec = { fadeIn(tween(LOOK_FADE_MS)) togetherWith fadeOut(tween(LOOK_FADE_MS)) },
        modifier = Modifier.fillMaxSize().background(surface),
        label = "welcomeLook",
    ) { (shownTheme, shownWallpaper) ->
        Box(modifier = Modifier.fillMaxSize().background(surface)) {
            if (shownWallpaper != null) {
                WallpaperImage(wallpaper = shownWallpaper, modifier = Modifier.fillMaxSize())
                val veil = remember(shownWallpaper, surface, text) { veilOf(shownWallpaper, surface, text) }
                Spacer(
                    modifier = Modifier.fillMaxSize().drawBehind {
                        drawVeil(veil.colors, veil.colors, 0f, veil.alpha)
                    },
                )
            } else {
                Spacer(
                    modifier = Modifier.fillMaxSize().drawBehind {
                        drawWorkspaceTheme(shownTheme, next = null, fraction = 0f, grain = grain)
                    },
                )
            }
        }
    }
}

/** A bar for each page, filled up to the [current] one. */
@Composable
private fun PageProgress(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.dejavu_features_page, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            val fill by animateFloatAsState(
                targetValue = if (index <= current) 1f else 0f,
                animationSpec = tween(PROGRESS_MS, easing = FastOutSlowInEasing),
                label = "welcomeProgress",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fill)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * Brings what it is on up into place as its page comes in, the [order]-th of the page, so a page's parts follow each
 * other in.
 */
@Composable
internal fun Modifier.rise(order: Int): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * RISE_STEP_MS)
        shown.animateTo(1f, tween(RISE_MS, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * RISE_DISTANCE.toPx()
    }
}

/** Lets a row that scrolls sideways run on to the edges of the page, past its [PAGE_MARGIN] at both sides. */
internal fun Modifier.toPageEdges(): Modifier = layout { measurable, constraints ->
    val margins = (PAGE_MARGIN * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + margins, maxWidth = constraints.maxWidth + margins),
    )
    layout(placeable.width - margins, placeable.height) { placeable.place(-margins / 2, 0) }
}

/** The top of a page: its [icon], [title] and [text]. */
@Composable
internal fun PageHeader(@DrawableRes icon: Int, @StringRes title: Int, @StringRes text: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(36.dp).rise(0),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.rise(1),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rise(2),
    )
    Spacer(Modifier.height(20.dp))
}

/** A heading over a group of choices. */
@Composable
internal fun ChoicesLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

/**
 * A choice on glass over the look behind the pages, outlined while it is [selected], with a radio button at its end.
 */
@Composable
internal fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .glass(glass, shape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
    ) {
        content()
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** How the pages scale coming in from behind the one leaving, or leaving toward the viewer. */
private const val BEHIND = 0.9f
private const val IN_FRONT = 1.08f
private const val ENTER_MS = 320
private const val ENTER_DELAY_MS = 90
private const val EXIT_MS = 200
private const val LOOK_FADE_MS = 450
private const val PROGRESS_MS = 300
private const val RISE_MS = 380
private const val RISE_STEP_MS = 45L
private const val SAVE_DELAY_MS = 400L
private const val TRACK_ALPHA = 0.15f
private val RISE_DISTANCE = 18.dp
private val MAX_WIDTH = 560.dp

/** The room at the sides of a page. */
internal val PAGE_MARGIN = 24.dp
