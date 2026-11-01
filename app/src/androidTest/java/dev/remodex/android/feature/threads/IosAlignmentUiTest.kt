package dev.remodex.android.feature.threads

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.remodex.android.model.*
import org.junit.Rule
import org.junit.Test

class IosAlignmentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryStartsExpandedAndDiffCanBeOpened() {
        val entry = TimelineEntry("f", "System", "Now", "", TimelineEntryKind.FileChange,
            structuredPayload = """{"changes":[{"path":"src/MarkdownBody.kt","diff":"-old\n+new"}]}""")
        compose.setContent { MaterialTheme { FileChangeSummaryCard(listOf(entry)) } }
        compose.onNodeWithText("MarkdownBody.kt").assertIsDisplayed().performClick()
        compose.onNodeWithText("+new").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("File changes").performClick()
        compose.onNodeWithText("MarkdownBody.kt").assertDoesNotExist()
    }

    @Test fun workspaceImageCardShowsPathAndPreviewFailureIsRecoverable() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalMessageInteractions provides MessageInteractions(readImage = { error("Image not found") })) {
                AssistantMarkdownBody("![Generated image](</tmp/example.png>)")
            }
        } }
        compose.onNodeWithText("/tmp/example.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview image").performClick()
        compose.onNodeWithText("Image not found").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("/tmp/example.png").assertIsDisplayed()
    }
}
