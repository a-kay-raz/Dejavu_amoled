/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.discover.NewThing
import org.mozilla.fenix.dejavu.discover.NewThings
import org.mozilla.fenix.dejavu.discover.isNewAmong
import org.mozilla.fenix.dejavu.discover.newDot
import org.mozilla.fenix.dejavu.discover.rememberUnseenNewThings
import org.mozilla.fenix.dejavu.menu.ActionsBarLayout
import org.mozilla.fenix.dejavu.menu.AddressBarButtons
import org.mozilla.fenix.dejavu.menu.BarSide
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuLayout
import org.mozilla.fenix.dejavu.ui.DejavuDropdownMenu
import org.mozilla.fenix.dejavu.ui.DejavuMenuItem
import org.mozilla.fenix.dejavu.ui.iconPainter

/** Where a button dragged in the editor of the "More" menu comes from. */
internal sealed interface MenuDragSource {
    val key: String

    /** A new button, from the list of every button. */
    data class New(override val key: String) : MenuDragSource

    /** The button at [index] of line [line]. */
    data class Placed(override val key: String, val line: Int, val index: Int) : MenuDragSource

    /** The button on [side] of the address bar. */
    data class Side(override val key: String, val side: BarSide) : MenuDragSource
}

/**
 * Changes to the lines of the editor of the "More" menu: the actions bar first, at [BAR], then the rows of the menu.
 * The bar holds up to [ActionsBarLayout.MAX_BUTTONS] buttons, Home and Search among them, which only the bar can hold.
 * A row holds up to [MoreMenuLayout.MAX_PER_ROW] buttons from its start, or a full row button alone, like
 * [MoreMenuLayout] lays them out, except that it can be empty until a button is put into it. A button can be on the
 * lines more than once.
 */
internal object MenuLinesEditing {
    const val BAR = 0

    /** How many blocks line [line] has. */
    fun capacityOf(line: Int): Int = if (line == BAR) ActionsBarLayout.MAX_BUTTONS else MoreMenuLayout.MAX_PER_ROW

    /**
     * The lines once the button of [source] is dropped on block [slot] of line [line], or `null` when it cannot go
     * there: a full row button only goes into an empty row, and nothing goes next to it. Dropped on a button, a button
     * goes before it. On a full line it takes that button's place: a new button replaces it, a moved one swaps with it.
     */
    fun drop(lines: List<List<String>>, source: MenuDragSource, line: Int, slot: Int): List<List<String>>? {
        val target = lines.getOrNull(line) ?: return null
        val placed = source as? MenuDragSource.Placed
        if (!fits(line, source.key)) return null
        return when {
            isFullRow(line, source.key) -> when {
                placed?.line == line -> lines
                target.isEmpty() -> lines.without(placed).withLine(line, listOf(source.key))
                else -> null
            }
            target.any { isFullRow(line, it) } -> null
            placed?.line == line -> {
                val keys = target.toMutableList()
                val moved = keys.removeAt(placed.index)
                keys.add(minOf(slot, keys.size), moved)
                lines.withLine(line, keys)
            }
            target.size < capacityOf(line) -> {
                val keys = target.toMutableList().apply { add(minOf(slot, size), source.key) }
                lines.without(placed).withLine(line, keys)
            }
            else -> {
                val replaced = slot.coerceIn(target.indices)
                val swapped = target[replaced]
                val updated = lines.withLine(line, target.withKey(replaced, source.key))
                when {
                    placed == null -> updated
                    fits(placed.line, swapped) && !isFullRow(placed.line, swapped) ->
                        updated.withLine(placed.line, lines[placed.line].withKey(placed.index, swapped))
                    else -> null
                }
            }
        }
    }

    /** The blocks of line [line] that the button of [source] takes when it is dropped on block [slot]. */
    fun landing(lines: List<List<String>>, source: MenuDragSource, line: Int, slot: Int): IntRange {
        val target = lines.getOrNull(line).orEmpty()
        val index = when {
            isFullRow(line, source.key) -> return 0 until capacityOf(line)
            (source as? MenuDragSource.Placed)?.line == line -> minOf(slot, target.size - 1)
            target.size < capacityOf(line) -> minOf(slot, target.size)
            else -> slot
        }
        return index..index
    }

    /** The lines without the button at [index] of line [line], which stays even when it is left empty. */
    fun remove(lines: List<List<String>>, line: Int, index: Int): List<List<String>> =
        lines.withLine(line, lines[line].filterIndexed { i, _ -> i != index })

    /**
     * The lines with [key] after the last button: on the bar for Home and Search while it has room, and else on the
     * last row, or on a new row when the last one has no room for it.
     */
    fun append(lines: List<List<String>>, key: String): List<List<String>> {
        if (ActionsBarLayout.isBarOnly(key)) {
            val bar = lines[BAR]
            return if (bar.size < capacityOf(BAR)) lines.withLine(BAR, bar + key) else lines
        }
        val last = lines.lastIndex
        val row = lines[last]
        val fits = last != BAR && if (MoreMenuLayout.isFullRow(key)) {
            row.isEmpty()
        } else {
            row.size < MoreMenuLayout.MAX_PER_ROW && row.none(MoreMenuLayout::isFullRow)
        }
        return if (fits) lines.withLine(last, row + key) else lines + listOf(listOf(key))
    }

    /** The lines with [key] in place of the button at [index] of line [line]. */
    fun replace(lines: List<List<String>>, line: Int, index: Int, key: String): List<List<String>> =
        lines.withLine(line, lines[line].withKey(index, key))

    /** Whether the button with [key] can go into line [line]: only the bar takes Home and Search. */
    fun fits(line: Int, key: String) = line == BAR || !ActionsBarLayout.isBarOnly(key)

    /** Whether the button with [key] fills line [line] on its own, as the extensions do in a row of the menu. */
    fun isFullRow(line: Int, key: String) = line != BAR && MoreMenuLayout.isFullRow(key)

    private fun List<List<String>>.withLine(index: Int, line: List<String>) =
        mapIndexed { i, old -> if (i == index) line else old }

    private fun List<String>.withKey(index: Int, key: String) = mapIndexed { i, old -> if (i == index) key else old }

    private fun List<List<String>>.without(placed: MenuDragSource.Placed?) =
        if (placed == null) this else remove(this, placed.line, placed.index)
}

/**
 * Changes to the buttons beside the address bar in the editor of the "More" menu, see [AddressBarButtons], and to the
 * lines of [MenuLinesEditing] as buttons move between the two. Each side holds one button, any the actions bar takes.
 */
internal object AddressBarEditing {
    /**
     * The buttons beside the address bar and the lines once the button of [source] is dropped on [side]. A button from
     * the other side swaps with the one there; one from a line leaves it, and the one on [side] takes its place there
     * when it can go there.
     */
    fun dropOnSide(
        sides: AddressBarButtons,
        lines: List<List<String>>,
        source: MenuDragSource,
        side: BarSide,
    ): Pair<AddressBarButtons, List<List<String>>> {
        val replaced = sides[side]
        return when (source) {
            is MenuDragSource.New -> sides.with(side, source.key) to lines
            is MenuDragSource.Side -> sides.with(side, source.key).with(source.side, replaced) to lines
            is MenuDragSource.Placed -> sides.with(side, source.key) to swapOut(lines, source, replaced)
        }
    }

    /**
     * The buttons beside the address bar and the lines once the button on [side] is dropped on block [slot] of line
     * [line], or `null` when it cannot go there. It goes there as a new button would and leaves [side]; when it takes
     * the place of a button of a full line, that one goes to [side] instead.
     */
    fun dropOnLine(
        sides: AddressBarButtons,
        lines: List<List<String>>,
        side: BarSide,
        line: Int,
        slot: Int,
    ): Pair<AddressBarButtons, List<List<String>>>? {
        val key = sides[side] ?: return null
        val target = lines.getOrNull(line) ?: return null
        val updated = MenuLinesEditing.drop(lines, MenuDragSource.New(key), line, slot) ?: return null
        val full = target.size >= MenuLinesEditing.capacityOf(line) && !MenuLinesEditing.isFullRow(line, key)
        return sides.with(side, if (full) target[slot.coerceIn(target.indices)] else null) to updated
    }

    /** The buttons beside the address bar with [key] on the first side without one; as they are when both have one. */
    fun fill(sides: AddressBarButtons, key: String): AddressBarButtons = when {
        sides.start == null -> sides.with(BarSide.START, key)
        sides.end == null -> sides.with(BarSide.END, key)
        else -> sides
    }

    /** The lines without the button of [placed], or with [other] in its place when it can go there. */
    private fun swapOut(lines: List<List<String>>, placed: MenuDragSource.Placed, other: String?): List<List<String>> {
        val line = placed.line
        val back = other?.takeIf {
            MenuLinesEditing.fits(line, it) && (lines[line].size == 1 || !MenuLinesEditing.isFullRow(line, it))
        }
        return if (back != null) {
            MenuLinesEditing.replace(lines, line, placed.index, back)
        } else {
            MenuLinesEditing.remove(lines, line, placed.index)
        }
    }
}

private val BlockHeight = 64.dp
private val BlockShape = RoundedCornerShape(14.dp)
private val BlockGap = 6.dp
private val LineEndWidth = 48.dp
private val FloatingWidth = 76.dp
private val AutoScrollEdge = 40.dp
private const val AUTO_SCROLL_STEP = 12f
private const val AUTO_SCROLL_FRAME_MS = 16L
private const val DRAGGED_ALPHA = 0.35f
private const val REMOVE_ALPHA = 0.85f
private const val FIELD_BLOCK_ALPHA = 0.06f
private val BLOCK_DOT_INSET = 8.dp

/** The blocks the address bar's field takes between its two sides, as wide as three buttons of the actions bar. */
private const val ADDRESS_FIELD_BLOCKS = 3

/** A button being dragged: where it comes from, where the finger is in root coordinates, and where it would land. */
private data class MenuDrag(val source: MenuDragSource, val pointer: Offset, val target: MenuDropTarget? = null)

/** Where a dragged button would land. */
private sealed interface MenuDropTarget {
    /** Block [slot] of line [line]. */
    data class Block(val line: Int, val slot: Int) : MenuDropTarget

    /** The block of [side] of the address bar. */
    data class Side(val side: BarSide) : MenuDropTarget

    /** Back among every button, which takes it off the lines. */
    data object Remove : MenuDropTarget
}

/** The layout of a dragged button, to turn finger positions on it into root ones. */
private class Anchor {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Edits the buttons beside the address bar, the actions bar and the rows of the "More" menu on a screen split in two:
 * the lines on top and every button below. The address bar comes first, with blocks for a button on each side of its
 * field, which takes the three blocks in the middle; then the actions bar with blocks for five buttons, which only
 * shows while the layout of the bars has it, see [BarsLayout]; then the rows with four. A button is held and dragged
 * into a block, from below or from another block, and dragged back down it leaves its block. Tapping a button below
 * adds it after the last one, or beside the address bar for Home and Search without an actions bar; tapping one in a
 * block offers to remove it. The actions bar cannot be deleted.
 */
@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
@Composable
internal fun MoreMenuLinesEditor(settings: DejavuSettings, modifier: Modifier = Modifier) {
    val customActions by settings.customActions.collectAsState()
    val layout by settings.barsLayout.collectAsState()
    var lines by remember {
        val bar = settings.actionsBarKeys.value.filter { ActionsBarLayout.entryOf(it, customActions) != null }
        val rows = settings.moreMenuRows.value.map { row ->
            row.filter { MoreMenuLayout.entryOf(it, customActions) != null }
        }
        mutableStateOf(listOf(bar) + rows)
    }
    var sides by remember { mutableStateOf(settings.addressBarButtonsIn(layout)) }
    val palette = remember(customActions) { ActionsBarLayout.available(customActions) }
    var drag by remember { mutableStateOf<MenuDrag?>(null) }
    var menuFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sideMenuFor by remember { mutableStateOf<BarSide?>(null) }
    val blocks = remember { mutableStateMapOf<Pair<Int, Int>, Rect>() }
    val sideBlocks = remember { mutableStateMapOf<BarSide, Rect>() }
    var linesArea by remember { mutableStateOf(Rect.Zero) }
    var paletteArea by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val linesScroll = rememberScrollState()
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { AutoScrollEdge.toPx() }
    val current = drag
    val target = current?.target as? MenuDropTarget.Block
    val sideTarget = current?.target as? MenuDropTarget.Side
    val dragged = current?.source as? MenuDragSource.Placed
    val draggedSide = current?.source as? MenuDragSource.Side
    val dragging = current != null
    // The new buttons keep their orange dot while the editor shows, and lose it once it closes.
    val unseen = rememberUnseenNewThings()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { NewThing.MENU_BUTTONS.forEach { NewThings.seen(context, it) } }
    }

    fun update(updated: List<List<String>>) {
        if (updated == lines) return
        if (updated[MenuLinesEditing.BAR] != lines[MenuLinesEditing.BAR]) {
            settings.setActionsBar(updated[MenuLinesEditing.BAR])
        }
        if (updated.drop(1) != lines.drop(1)) settings.setMoreMenuRows(updated.drop(1))
        lines = updated
    }

    fun updateWithSides(updated: Pair<AddressBarButtons, List<List<String>>>) {
        update(updated.second)
        if (updated.first == sides) return
        settings.setAddressBarButtons(updated.first)
        sides = updated.first
    }

    fun targetAt(source: MenuDragSource, pointer: Offset): MenuDropTarget? {
        if (paletteArea.contains(pointer)) return MenuDropTarget.Remove.takeIf { source !is MenuDragSource.New }
        sideBlocks.entries.firstOrNull { it.value.contains(pointer) }?.let { return MenuDropTarget.Side(it.key) }
        val (line, slot) = blocks.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return null
        val fits = if (source is MenuDragSource.Side) {
            AddressBarEditing.dropOnLine(sides, lines, source.side, line, slot) != null
        } else {
            MenuLinesEditing.drop(lines, source, line, slot) != null
        }
        return MenuDropTarget.Block(line, slot).takeIf { fits }
    }

    fun startDrag(source: MenuDragSource, pointer: Offset) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuFor = null
        sideMenuFor = null
        drag = MenuDrag(source, pointer, targetAt(source, pointer))
    }

    fun moveDrag(pointer: Offset) {
        val moving = drag ?: return
        drag = moving.copy(pointer = pointer, target = targetAt(moving.source, pointer))
    }

    fun endDrag() {
        val ended = drag ?: return
        drag = null
        val source = ended.source
        when (val landed = ended.target) {
            is MenuDropTarget.Side -> updateWithSides(AddressBarEditing.dropOnSide(sides, lines, source, landed.side))
            is MenuDropTarget.Block -> if (source is MenuDragSource.Side) {
                val dropped = AddressBarEditing.dropOnLine(sides, lines, source.side, landed.line, landed.slot)
                dropped?.let(::updateWithSides)
            } else {
                MenuLinesEditing.drop(lines, source, landed.line, landed.slot)?.let(::update)
            }
            MenuDropTarget.Remove -> when (source) {
                is MenuDragSource.Placed -> update(MenuLinesEditing.remove(lines, source.line, source.index))
                is MenuDragSource.Side -> updateWithSides(sides.with(source.side, null) to lines)
                is MenuDragSource.New -> Unit
            }
            null -> Unit
        }
    }

    // Lines out of sight scroll into it while a button is held near the top or the bottom of the lines. An edge only
    // scrolls once the button was away from it, so one brought up from the buttons below, which comes in over the
    // bottom edge, does not scroll the lines away from where it goes.
    LaunchedEffect(dragging) {
        var awayFromTop = false
        var awayFromBottom = false
        while (dragging) {
            val pointer = drag?.pointer ?: break
            val inLines = pointer.x in linesArea.left..linesArea.right && pointer.y in linesArea.top..linesArea.bottom
            if (inLines && pointer.y >= linesArea.top + edge) awayFromTop = true
            if (inLines && pointer.y <= linesArea.bottom - edge) awayFromBottom = true
            val step = when {
                !inLines -> 0f
                pointer.y < linesArea.top + edge && awayFromTop -> -AUTO_SCROLL_STEP
                pointer.y > linesArea.bottom - edge && awayFromBottom -> AUTO_SCROLL_STEP
                else -> 0f
            }
            if (step != 0f) linesScroll.scrollBy(step)
            val latest = drag ?: break
            val updated = targetAt(latest.source, latest.pointer)
            if (updated != latest.target) drag = latest.copy(target = updated)
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    @Composable
    fun Line(line: Int, keys: List<String>) {
        val landing = if (current != null && target != null && target.line == line) {
            MenuLinesEditing.landing(lines, current.source, line, target.slot)
        } else {
            null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(BlockGap), modifier = Modifier.weight(1f)) {
                val wide = keys.singleOrNull()?.takeIf { line != MenuLinesEditing.BAR && MoreMenuLayout.isFullRow(it) }
                val slots = if (wide != null) listOf(0) else (0 until MenuLinesEditing.capacityOf(line)).toList()
                slots.forEach { slot ->
                    val key = keys.getOrNull(slot)
                    val entry = key?.let { ActionsBarLayout.entryOf(it, customActions) }
                    DisposableEffect(line, slot) { onDispose { blocks.remove(line to slot) } }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { blocks[line to slot] = it.boundsInRoot() },
                    ) {
                        val highlighted = landing != null && (wide != null || slot in landing)
                        if (key != null && entry != null) {
                            DraggableBlock(
                                onDragStart = { startDrag(MenuDragSource.Placed(key, line, slot), it) },
                                onDrag = ::moveDrag,
                                onDragEnd = ::endDrag,
                                onDragCancel = { drag = null },
                                onClick = { if (drag == null) menuFor = line to slot },
                            ) {
                                BlockContent(
                                    entry = entry,
                                    highlighted = highlighted,
                                    dimmed = dragged?.line == line && dragged.index == slot,
                                    wide = wide != null,
                                    isNew = entry.isNewAmong(unseen, leadsToSettings = false),
                                )
                            }
                            DejavuDropdownMenu(
                                expanded = menuFor == line to slot,
                                onDismissRequest = { menuFor = null },
                            ) {
                                DejavuMenuItem(
                                    text = stringResource(R.string.dejavu_more_menu_remove),
                                    onClick = {
                                        menuFor = null
                                        update(MenuLinesEditing.remove(lines, line, slot))
                                    },
                                )
                            }
                        } else {
                            BlockContent(entry = null, highlighted = highlighted)
                        }
                    }
                }
            }
            if (line == MenuLinesEditing.BAR) {
                Spacer(Modifier.width(LineEndWidth))
            } else {
                IconButton(
                    onClick = { update(lines.filterIndexed { index, _ -> index != line }) },
                    enabled = !dragging,
                    modifier = Modifier.size(LineEndWidth),
                ) {
                    Icon(
                        painter = iconPainter(iconsR.drawable.mozac_ic_cross_24),
                        contentDescription = stringResource(R.string.dejavu_more_menu_delete_row),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    @Composable
    fun SideBlock(side: BarSide, modifier: Modifier) {
        val key = sides[side]
        val entry = key?.let { ActionsBarLayout.entryOf(it, customActions) }
        val highlighted = sideTarget?.side == side
        DisposableEffect(side) { onDispose { sideBlocks.remove(side) } }
        Box(modifier = modifier.onGloballyPositioned { sideBlocks[side] = it.boundsInRoot() }) {
            if (key != null && entry != null) {
                DraggableBlock(
                    onDragStart = { startDrag(MenuDragSource.Side(key, side), it) },
                    onDrag = ::moveDrag,
                    onDragEnd = ::endDrag,
                    onDragCancel = { drag = null },
                    onClick = { if (drag == null) sideMenuFor = side },
                ) {
                    BlockContent(
                        entry = entry,
                        highlighted = highlighted,
                        dimmed = draggedSide?.side == side,
                        isNew = entry.isNewAmong(unseen, leadsToSettings = false),
                    )
                }
                DejavuDropdownMenu(expanded = sideMenuFor == side, onDismissRequest = { sideMenuFor = null }) {
                    DejavuMenuItem(
                        text = stringResource(R.string.dejavu_more_menu_remove),
                        onClick = {
                            sideMenuFor = null
                            updateWithSides(sides.with(side, null) to lines)
                        },
                    )
                }
            } else {
                BlockContent(entry = null, highlighted = highlighted)
            }
        }
    }

    // The address bar's field takes the blocks between its two sides, which take one button each.
    @Composable
    fun AddressLine() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(BlockGap), modifier = Modifier.weight(1f)) {
                SideBlock(BarSide.START, Modifier.weight(1f))
                repeat(ADDRESS_FIELD_BLOCKS) { block ->
                    FieldBlock(showsIcon = block == ADDRESS_FIELD_BLOCKS / 2, modifier = Modifier.weight(1f))
                }
                SideBlock(BarSide.END, Modifier.weight(1f))
            }
            Spacer(Modifier.width(LineEndWidth))
        }
    }

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(BlockGap),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { linesArea = it.boundsInRoot() }
                    .verticalScroll(linesScroll, enabled = !dragging)
                    .padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                LineCaption(stringResource(R.string.dejavu_more_menu_address_bar))
                AddressLine()
                if (layout.hasActionsBar) {
                    LineCaption(stringResource(R.string.dejavu_settings_actions_bar))
                    Line(MenuLinesEditing.BAR, lines[MenuLinesEditing.BAR])
                }
                LineCaption(stringResource(R.string.dejavu_settings_more_menu))
                lines.forEachIndexed { line, keys -> if (line != MenuLinesEditing.BAR) Line(line, keys) }
                OutlinedButton(
                    onClick = { update(lines + listOf(emptyList())) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, end = 12.dp),
                ) {
                    Text(stringResource(R.string.dejavu_more_menu_add_row))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { paletteArea = it.boundsInRoot() },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(BlockGap),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = !dragging)
                        .padding(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dejavu_more_menu_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    palette.chunked(MoreMenuLayout.MAX_PER_ROW).forEach { chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
                            chunk.forEach { entry ->
                                DraggableBlock(
                                    onDragStart = { startDrag(MenuDragSource.New(entry.key), it) },
                                    onDrag = ::moveDrag,
                                    onDragEnd = ::endDrag,
                                    onDragCancel = { drag = null },
                                    onClick = {
                                        when {
                                            drag != null -> Unit
                                            // Without an actions bar, Home and Search only go beside the address bar.
                                            !layout.hasActionsBar && ActionsBarLayout.isBarOnly(entry.key) ->
                                                updateWithSides(AddressBarEditing.fill(sides, entry.key) to lines)
                                            else -> update(MenuLinesEditing.append(lines, entry.key))
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    BlockContent(
                                        entry = entry,
                                        isNew = entry.isNewAmong(unseen, leadsToSettings = false),
                                    )
                                }
                            }
                            repeat(MoreMenuLayout.MAX_PER_ROW - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (dragged != null || draggedSide != null) RemoveArea(active = current.target == MenuDropTarget.Remove)
            }
        }
        if (current != null) {
            ActionsBarLayout.entryOf(current.source.key, customActions)?.let { entry ->
                FloatingBlock(entry = entry, position = current.pointer - origin)
            }
        }
    }
}

/** The name of the lines below it: the address bar, the actions bar, or the rows of the "More" menu. */
@Composable
private fun LineCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * One of the blocks the address bar's field takes in its line, which no button goes into; the middle one shows the
 * search icon.
 */
@Composable
private fun FieldBlock(showsIcon: Boolean, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(BlockHeight)
            .clip(BlockShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = FIELD_BLOCK_ALPHA)),
    ) {
        if (showsIcon) {
            Icon(
                painter = iconPainter(R.drawable.dejavu_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A button that a long press starts dragging, with finger positions in root coordinates. A tap runs [onClick]. */
@Suppress("LongParameterList")
@Composable
private fun DraggableBlock(
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val anchor = remember { Anchor() }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = modifier
            .onGloballyPositioned { anchor.coordinates = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> anchor.coordinates?.let { start(it.localToRoot(offset)) } },
                    onDrag = { change, _ ->
                        change.consume()
                        anchor.coordinates?.let { move(it.localToRoot(change.position)) }
                    },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                )
            }
            .clickable(onClick = onClick),
    ) {
        content()
    }
}

/**
 * A block of a line, or of the list of every button: [entry]'s icon and name, or an empty outlined block without one.
 * A [wide] block holds a full row button, with its name next to its icon. A button that [isNew] has the orange dot.
 */
@Composable
private fun BlockContent(
    entry: MoreMenuEntry?,
    highlighted: Boolean = false,
    dimmed: Boolean = false,
    wide: Boolean = false,
    isNew: Boolean = false,
) {
    val border = when {
        highlighted -> MaterialTheme.colorScheme.primary
        entry == null -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(BlockHeight)
            .alpha(if (dimmed) DRAGGED_ALPHA else 1f)
            .newDot(isNew, inset = BLOCK_DOT_INSET)
            .clip(BlockShape)
            .background(if (entry == null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (highlighted) 2.dp else 1.dp, border, BlockShape)
            .padding(horizontal = 4.dp),
    ) {
        when {
            entry == null -> Unit
            wide -> Row(verticalAlignment = Alignment.CenterVertically) {
                BlockIcon(entry)
                Spacer(Modifier.width(10.dp))
                BlockLabel(entry)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BlockIcon(entry)
                Spacer(Modifier.height(4.dp))
                BlockLabel(entry)
            }
        }
    }
}

@Composable
private fun BlockIcon(entry: MoreMenuEntry) {
    Icon(
        painter = iconPainter(entry.icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun BlockLabel(entry: MoreMenuEntry) {
    Text(
        text = entry.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Covers the list of every button while a button of a line is dragged, where dropping it takes it off the line. */
@Composable
private fun RemoveArea(active: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = if (active) 1f else REMOVE_ALPHA)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = iconPainter(iconsR.drawable.mozac_ic_delete_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dejavu_more_menu_remove),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** The dragged button, under the finger at [position] in the editor's coordinates. */
@Composable
private fun FloatingBlock(entry: MoreMenuEntry, position: Offset) {
    val density = LocalDensity.current
    val halfWidth = with(density) { FloatingWidth.toPx() } / 2
    val halfHeight = with(density) { BlockHeight.toPx() } / 2
    Box(
        modifier = Modifier
            .offset { IntOffset((position.x - halfWidth).roundToInt(), (position.y - halfHeight).roundToInt()) }
            .width(FloatingWidth)
            .shadow(8.dp, BlockShape)
            .clip(BlockShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        BlockContent(entry = entry)
    }
}
