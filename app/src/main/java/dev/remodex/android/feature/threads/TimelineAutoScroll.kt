package dev.remodex.android.feature.threads

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Keep following layout growth until the reader scrolls toward older messages. */
@Composable
internal fun rememberTimelineAutoScroll(listState: LazyListState): NestedScrollConnection {
    var followLatest by remember(listState) { mutableStateOf(true) }
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Content moves down when the reader scrolls toward older messages.
                // Stop before the gesture moves the list, even if a reply arrives mid-drag.
                if (available.y > 0f && listState.canScrollBackward) followLatest = false
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if ((consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) {
                    followLatest = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        // Observe measured layout, not only message count: streaming and image loading
        // can increase the height of the last message without adding a new row.
        snapshotFlow {
            if (followLatest && !listState.isScrollInProgress && listState.canScrollForward) {
                listState.layoutInfo.totalItemsCount
            } else 0
        }.collect { count ->
            if (count > 0 && followLatest) {
                listState.scrollToItem(count - 1)
                // A single reply may be taller than the viewport. Reveal its bottom too.
                if (followLatest) {
                    val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastItem?.index == count - 1) listState.scrollBy(lastItem.size.toFloat())
                }
            }
        }
    }
    return connection
}
