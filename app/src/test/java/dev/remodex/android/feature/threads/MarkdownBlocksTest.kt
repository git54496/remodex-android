package dev.remodex.android.feature.threads

import org.junit.Assert.*
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun unorderedItemsHaveRealMarkersAndContinuationText() {
        val group = markdownBlocks("- **iOS**: history works\n  including older messages\n- Android: connected").single() as MarkdownBlock.ListGroup
        assertEquals(listOf("•", "•"), group.items.map { it.marker })
        assertEquals("**iOS**: history works\nincluding older messages", group.items.first().text)
    }
    @Test fun preservesNumberingAndNestedIndentation() {
        val group = markdownBlocks("3. First\n   - Nested\n4) Second").single() as MarkdownBlock.ListGroup
        assertEquals(listOf("3.", "•", "4)"), group.items.map { it.marker })
        assertEquals(listOf(0, 3, 0), group.items.map { it.indent })
    }
    @Test fun blankLineSeparatesFollowingProseFromList() {
        val blocks = markdownBlocks("Introduction\n\n- Item\n\nFinal paragraph")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Final paragraph"), blocks.last())
    }
    @Test fun codeFencesPreserveLiteralBulletsAndUnfinishedStreamingCode() {
        val blocks = markdownBlocks("```kotlin\n- literal\n```\n\n- Actual list")
        assertEquals(MarkdownBlock.Code("- literal"), blocks.first())
        assertTrue(blocks.last() is MarkdownBlock.ListGroup)
        assertEquals(MarkdownBlock.Code("  indented\n- literal"), markdownBlocks("~~~\n  indented\n- literal").single())
    }
    @Test fun nestedContinuationAndMultipleParagraphsStayInItem() {
        val group = markdownBlocks("- First\n\n  Second paragraph\n- Next").single() as MarkdownBlock.ListGroup
        assertEquals("First\n\nSecond paragraph", group.items.first().text)
        assertEquals(2, group.items.size)
    }
    @Test fun headingsAreNotPartOfPreviousList() {
        val blocks = markdownBlocks("- Item\n## Result\nBody")
        assertEquals(MarkdownBlock.Heading("Result", 2), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("Body"), blocks.last())
    }
}
