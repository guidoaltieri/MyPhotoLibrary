package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import io.github.guidoaltieri.myphotolibrary.data.Thumb
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item

/** Elementi selezionati in una griglia. */
class Selection {
    val map = mutableStateMapOf<Long, Boolean>()
    val active get() = map.isNotEmpty()
    val ids: List<Long> get() = map.keys.toList()
    val size get() = map.size
    fun toggle(id: Long) {
        if (map.containsKey(id)) map.remove(id) else map[id] = true
    }
    fun contains(id: Long) = map.containsKey(id)
    fun clear() = map.clear()
    fun setAll(ids: Collection<Long>) {
        map.clear(); ids.forEach { map[it] = true }
    }
}

@Composable
fun rememberSelection() = remember { Selection() }

/** Zoom delle miniature con due dita (pizzico): meno o più colonne. */
fun Modifier.pinchColumns(key: Any, onChange: (Int) -> Unit) = pointerInput(key) {
    awaitEachGesture {
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (zoom > 1.3f) { onChange(-1); zoom = 1f } else if (zoom < 0.75f) { onChange(+1); zoom = 1f }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Griglia di foto e video: tocco per aprire, pressione prolungata per selezionare, pizzico per
 * cambiare la dimensione delle miniature, data in sovrimpressione durante lo scorrimento.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<Item>,
    selection: Selection,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    columns: Int = 4,
    onColumns: ((Int) -> Unit)? = null,
    scrollToEnd: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    if (scrollToEnd) LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) state.scrollToItem(items.size - 1 + (if (header != null) 1 else 0))
    }
    val overlay by remember(items) {
        derivedStateOf {
            val i = (state.firstVisibleItemIndex - (if (header != null) 1 else 0)).coerceIn(0, maxOf(0, items.size - 1))
            items.getOrNull(i)?.let { Fmt.month(it.taken) } ?: ""
        }
    }
    val conf = LocalConfiguration.current
    val thumbSize = Thumb.sizeFor((conf.screenWidthDp * conf.densityDpi / 160) / columns)
    Box(modifier.fillMaxSize().let { m -> if (onColumns != null) m.pinchColumns(columns) { d -> onColumns((columns + d).coerceIn(1, 10)) } else m }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            header?.invoke(this)
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                val selected = selection.contains(item.id)
                Box(
                    Modifier.aspectRatio(1f).padding(1.dp)
                        .combinedClickable(
                            onClick = { if (selection.active) selection.toggle(item.id) else onOpen(index) },
                            onLongClick = { selection.toggle(item.id) },
                        )
                ) {
                    ThumbImage(item, Modifier.fillMaxSize(), size = thumbSize)
                    ThumbBadges(item, compact = columns >= 7)
                    if (selection.active) {
                        if (selected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)).border(2.dp, MaterialTheme.colorScheme.primary))
                        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).background(
                                if (selected) Color.White else Color.Transparent, CircleShape))
                    }
                }
            }
        }
        AnimatedVisibility(state.isScrollInProgress && overlay.isNotEmpty() && items.size > 30, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            Text(overlay, color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** Intestazione che occupa tutta la riga della griglia. */
fun LazyGridScope.fullRow(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header") { content() }
}
