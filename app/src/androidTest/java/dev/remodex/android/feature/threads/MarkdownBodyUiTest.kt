package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.TimelineEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownBodyUiTest {
    @get:Rule val compose = createComposeRule()
    private fun reply(body: String) = TimelineEntry("markdown", "Codex", "Now", body)
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(text, substring = true) and
                hasAnyAncestor(hasTestTag("rendered-markdown"))).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun show(body: String) {
        compose.setContent { MaterialTheme { Box(Modifier.width(300.dp)) { AssistantMessageContent(reply(body)) } } }
    }
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        // Capture our Compose surface without claiming Android's shared UiAutomation service.
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun assertBoldText(text: String, bold: String) {
        val rendered = compose.onNodeWithText(text, substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertFalse(rendered.text.contains("**"))
        assertTrue(rendered.spanStyles.any {
            it.item.fontWeight == FontWeight.Bold && rendered.text.substring(it.start, it.end) == bold
        })
    }

    @Test fun chinesePunctuationRendersBoldInProseAndTables() {
        show("**验证限制：**本次\n\n这是**“重点”**说明\n\n| 内容 |\n| --- |\n| **表格：**正常 |")
        awaitText("表格：正常")
        assertBoldText("验证限制：本次", "验证限制：")
        assertBoldText("这是“重点”说明", "“重点”")
        assertBoldText("表格：正常", "表格：")
        compose.onNodeWithTag("assistant-prose").assertWidthIsEqualTo(300.dp)
        capture("markdown-cjk.png")
    }

    @Test fun streamingChineseBoldAppearsWhenClosingMarkersArrive() {
        var source by mutableStateOf("**验证限制：")
        compose.setContent { MaterialTheme { AssistantMessageContent(reply(source).copy(isStreaming = true)) } }
        awaitText("**验证限制：")
        compose.runOnIdle { source += "**本次" }
        awaitText("验证限制：本次")
        assertBoldText("验证限制：本次", "验证限制：")
        compose.onNodeWithText("**验证限制：").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy reply").assertDoesNotExist()
    }

    @Test fun chineseBoldCopyKeepsOriginalMarkdownWithoutInjectedCharacters() {
        val source = "**验证限制：**本次，这是**“重点”**说明。"
        show(source)
        awaitText("验证限制：本次，这是“重点”说明。")
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        var previous: android.content.ClipData? = null
        compose.runOnIdle { previous = clipboard.primaryClip }
        try {
            compose.onNodeWithContentDescription("Copy reply").performClick()
            compose.runOnIdle { assertEquals(source, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        } finally {
            compose.runOnIdle { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    @Test fun tableScrollsAndLongCellsWrapInsideFlatReply() {
        val longCell = "This cell contains enough words to wrap across several lines without losing any content."
        show("| Device | Notes | Status |\n| --- | --- | --- |\n| Android | $longCell | Ready |")
        awaitText("Device")
        compose.onNodeWithTag("assistant-prose").assertWidthIsEqualTo(300.dp)
        compose.onNodeWithText("Device", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Android", substring = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longCell, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        capture("markdown-table.png")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performTouchInput { swipeLeft() }
        compose.onNodeWithText("Ready", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy reply").assertIsDisplayed()
    }

    @Test fun headingsQuotesTasksAndInlineStylesUseGfmRendering() {
        show("# Heading\n\n> Quoted paragraph\n\n- [x] Complete\n- [ ] Pending\n\n**bold** *italic* ~~deleted~~ `inline`\n\n---\n\nAfter rule")
        awaitText("Quoted paragraph")
        compose.onNodeWithText("Heading").assertIsDisplayed()
        compose.onNodeWithText("Complete").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("After rule").assertIsDisplayed()
        val text = compose.onNodeWithText("bold italic deleted", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        // Inline code has visual padding spaces supplied by the library.
        assertEquals("bold italic deleted inline", text.text.trim().replace(Regex("\\s+"), " "))
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(text.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        capture("markdown-formats.png")
    }

    @Test fun linksOpenThroughAndroidUriHandler() {
        var opened: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) { MaterialTheme { AssistantMessageContent(reply("[Documentation](https://example.com/docs)")) } }
        }
        awaitText("Documentation")
        compose.onNodeWithText("Documentation").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("https://example.com/docs", opened) }
    }

    @Test fun unsupportedLinkDoesNotCrashConversation() {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw IllegalArgumentException("No handler") }
            }) { MaterialTheme { AssistantMessageContent(reply("[Local file](file:///tmp/example.kt)")) } }
        }
        awaitText("Local file")
        compose.onNodeWithText("Local file").performTouchInput { click(center) }
        compose.onNodeWithTag("assistant-prose").assertIsDisplayed()
    }

    @Test fun incompleteFenceAndStreamingReplacementStayReadable() {
        var body by mutableStateOf("First paragraph\n\n```kotlin\nval count = 1")
        compose.setContent { MaterialTheme { AssistantMessageContent(reply(body).copy(isStreaming = true)) } }
        awaitText("First paragraph")
        compose.onNodeWithText("val count = 1", substring = true).assertIsDisplayed()
        compose.runOnIdle { body += "\nval next = count + 1\n```\n\nFinished" }
        awaitText("Finished")
        compose.onNodeWithText("val next = count + 1", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy reply").assertDoesNotExist()
        // Canonical history may replace rather than append to the streaming text.
        compose.runOnIdle { body = "## Replacement\n\nNew answer" }
        awaitText("New answer")
        compose.onNodeWithText("First paragraph").assertDoesNotExist()
        compose.onNodeWithText("Replacement").assertIsDisplayed()
    }
}
