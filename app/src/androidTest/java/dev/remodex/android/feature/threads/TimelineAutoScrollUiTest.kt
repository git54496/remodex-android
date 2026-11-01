package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimelineAutoScrollUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private val count = mutableIntStateOf(12)
    private val lastHeight = mutableIntStateOf(90)

    private fun showTimeline() {
        compose.setContent {
            list = rememberLazyListState()
            val follow = rememberTimelineAutoScroll(list)
            LazyColumn(
                state = list,
                modifier = Modifier.height(300.dp).fillMaxWidth()
                    .testTag("timeline").nestedScroll(follow),
            ) {
                items((0 until count.intValue).toList(), key = { it }) { index ->
                    Box(Modifier.fillMaxWidth().height(
                        (if (index == count.intValue - 1) lastHeight.intValue else 90).dp,
                    ))
                }
            }
        }
        awaitBottom()
    }

    private fun awaitBottom() {
        compose.waitUntil(5_000) {
            list.layoutInfo.totalItemsCount == count.intValue &&
                !list.isScrollInProgress && !list.canScrollForward
        }
    }

    @Test fun draggingShortConversationDoesNotDisableFollowing() {
        count.intValue = 2
        showTimeline()
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { count.intValue = 12 }
        compose.waitForIdle()
        awaitBottom()
    }

    @Test fun followsNewMessagesAndStreamingGrowthIncludingTallReplies() {
        showTimeline()
        compose.runOnIdle { count.intValue++ }
        awaitBottom()
        compose.runOnIdle { lastHeight.intValue = 900 }
        compose.waitForIdle()
        awaitBottom()
        compose.runOnIdle { assertTrue(list.firstVisibleItemScrollOffset > 0) }
        compose.runOnIdle { lastHeight.intValue = 1200 }
        compose.waitForIdle()
        awaitBottom()
    }

    @Test fun readingHistoryPreservesPositionAcrossAppendAndStreamingThenResumesAtBottom() {
        showTimeline()
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var index = 0
        var offset = 0
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            index = list.firstVisibleItemIndex
            offset = list.firstVisibleItemScrollOffset
            count.intValue++
        }
        compose.waitForIdle()
        compose.runOnIdle { lastHeight.intValue = 600 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
        repeat(10) {
            compose.onNodeWithTag("timeline").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        awaitBottom()
        compose.runOnIdle { count.intValue++ }
        compose.waitForIdle()
        awaitBottom()
    }
}
