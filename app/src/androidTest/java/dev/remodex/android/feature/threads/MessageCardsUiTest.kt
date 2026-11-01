package dev.remodex.android.feature.threads

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.remodex.android.model.*
import org.junit.Rule
import org.junit.Test

class MessageCardsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun show(kind: TimelineEntryKind, payload: String) {
        compose.setContent { MaterialTheme { SpecializedMessageCard(TimelineEntry("test", "System", "Now", "", kind, structuredPayload = payload)) } }
    }
    @Test fun fileDiffOpensAndCloses() {
        show(TimelineEntryKind.FileChange, """{"changes":[{"path":"src/Test.kt","kind":"update","diff":"-old\n+new"}]}""")
        compose.onNodeWithText("+1").assertIsDisplayed()
        compose.onNodeWithText("src/Test.kt").performClick()
        compose.onNodeWithText("+new").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("+new").assertDoesNotExist()
    }
    @Test fun subagentDetailsExpand() {
        show(TimelineEntryKind.SubagentAction, """{"receiverThreadIds":["agent-a"],"agentNickname":"Ada","status":"completed","prompt":"Review the cache","model":"test-model"}""")
        compose.onNodeWithText("Ada  ·  completed").performClick()
        compose.onNodeWithText("Review the cache").assertIsDisplayed()
        compose.onNodeWithText("Model: test-model").assertIsDisplayed()
    }
    @Test fun answeredQuestionsAreReadable() {
        show(TimelineEntryKind.Questions, """{"questions":[{"title":"Can you see history?"}],"resolvedAnswers":["Yes"]}""")
        compose.onNodeWithText("Can you see history?").assertIsDisplayed()
        compose.onNodeWithText("Yes").assertIsDisplayed()
        compose.onNodeWithText("✓  Answered").assertIsDisplayed()
    }
}
