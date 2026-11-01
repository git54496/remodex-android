package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantProseUiTest {
    @get:Rule val compose = createComposeRule()
    private val reply = TimelineEntry("reply", "Codex", "12:34", "Confirmed **both devices**.\n\n- iOS connected\n- Android connected")
    @Test fun assistantUsesFullWidthWithoutSpeakerOrTime() {
        compose.setContent { MaterialTheme { Box(Modifier.width(300.dp)) { TimelineMessageContent(reply) } } }
        compose.onNodeWithTag("assistant-prose").assertWidthIsEqualTo(300.dp)
        compose.onNodeWithText("Codex").assertDoesNotExist()
        compose.onNodeWithText("12:34").assertDoesNotExist()
        compose.onAllNodesWithText("•").assertCountEquals(2)
        compose.onNodeWithText("iOS connected").assertIsDisplayed()
    }
    @Test fun copyPreservesCompleteMarkdownAndShowsFeedback() {
        lateinit var clipboard: ClipboardManager
        compose.setContent { clipboard = LocalClipboardManager.current; MaterialTheme { AssistantMessageContent(reply) } }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val systemClipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        var previous: android.content.ClipData? = null
        compose.runOnIdle { previous = systemClipboard.primaryClip }
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Copy reply").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithContentDescription("Copied").assertIsDisplayed()
            compose.runOnIdle { assertEquals(reply.body, clipboard.getText()?.text) }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { previous?.let(systemClipboard::setPrimaryClip) ?: systemClipboard.clearPrimaryClip() }
        }
    }
    @Test fun streamingReplyDoesNotExposePrematureCopy() {
        compose.setContent { MaterialTheme { AssistantMessageContent(reply.copy(isStreaming = true)) } }
        compose.onNodeWithContentDescription("Copy reply").assertDoesNotExist()
        compose.onNodeWithTag("assistant-prose").assertIsDisplayed()
    }
    @Test fun previousMessagesDividerSurvivesExpandAndCollapse() {
        compose.setContent { MaterialTheme { ProjectedTimelineRow(TimelineRow("previous", listOf(reply), "1 previous message", true)) } }
        compose.onNodeWithTag("previous-messages-divider").assertIsDisplayed()
        compose.onNodeWithText("1 previous message").performClick()
        compose.onNodeWithTag("assistant-prose").assertIsDisplayed()
        compose.onNodeWithTag("previous-messages-divider").assertIsDisplayed()
        compose.onNodeWithText("1 previous message").performClick()
        compose.onNodeWithTag("assistant-prose").assertDoesNotExist()
    }
    @Test fun numberedAndNestedListsRenderAsSeparateMarkers() {
        compose.setContent { MaterialTheme { AssistantMessageContent(reply.copy(body = "3. Outer item\n   - Nested item\n4. Next item")) } }
        compose.onNodeWithText("3.").assertIsDisplayed()
        compose.onNodeWithText("4.").assertIsDisplayed()
        val outer = compose.onNodeWithText("Outer item").fetchSemanticsNode().boundsInRoot
        val nested = compose.onNodeWithText("Nested item").fetchSemanticsNode().boundsInRoot
        assertTrue(nested.left > outer.left)
    }
    @Test fun userMessageRetainsBubbleAndExternalTimestamp() {
        compose.setContent { MaterialTheme { TimelineMessageContent(reply.copy(speaker = "You")) } }
        compose.onNodeWithTag("assistant-prose").assertDoesNotExist()
        compose.onNodeWithText("12:34").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy reply").assertDoesNotExist()
    }
}
