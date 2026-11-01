package dev.remodex.android.feature.threads

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class CjkMarkdownFlavourTest {
    private fun parse(source: String) = MarkdownParser(CjkMarkdownFlavour()).buildMarkdownTreeFromString(source)
    private fun nodes(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun strong(source: String): List<String> = nodes(parse(source))
        .filter { it.type == MarkdownElementTypes.STRONG }
        .map { source.substring(it.startOffset + 2, it.endOffset - 2) }
    private fun tree(node: ASTNode): String = "${node.type}[${node.startOffset},${node.endOffset}]" +
        node.children.joinToString(prefix = "(", postfix = ")", transform = ::tree)
    private fun assertStandard(source: String) {
        assertEquals(source, tree(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)), tree(parse(source)))
    }

    @Test fun chinesePunctuationCanCloseStrongBeforeUnspacedText() {
        for (punctuation in listOf("：", "，", "。", "；", "！", "？", "、", "…", "—", "）", "」", "》", "”")) {
            val label = "验证限制$punctuation"
            assertEquals(label, listOf(label), strong("**$label**本次"))
        }
    }

    @Test fun chineseOpeningPunctuationCanOpenStrongAfterUnspacedText() {
        for (label in listOf("“重点”", "‘重点’", "（重点）", "「重点」", "《重点》", "【重点】", "：说明")) {
            assertEquals(label, listOf(label), strong("这是**$label**说明"))
        }
    }

    @Test fun adjacentStrongSpansKeepTheirOwnBoundaries() {
        assertEquals(listOf("第一：", "第二：", "第三："), strong("**第一：**正文**第二：**继续**第三：**结束"))
    }

    @Test fun mixedChineseAndLatinTextIsSupported() {
        assertEquals(listOf("注意："), strong("**注意：**English"))
        assertEquals(listOf("（重点）"), strong("prefix**（重点）**suffix"))
    }

    @Test fun boldWorksInsideTablesListsQuotesAndLinkLabels() {
        for (source in listOf(
            "| 内容 |\n| --- |\n| **注意：**正文 |",
            "- **注意：**正文", "> **注意：**正文", "[**注意：**正文](https://example.com)",
        )) assertEquals(source, listOf("注意："), strong(source))
    }

    @Test fun nestedItalicAndStrikethroughRemainInsideStrong() {
        val source = "**注意 *重点* ~~旧版~~：**正文"
        assertEquals(listOf("注意 *重点* ~~旧版~~："), strong(source))
        assertTrue(nodes(parse(source)).any { it.type == MarkdownElementTypes.EMPH })
        assertTrue(nodes(parse(source)).any { it.type == GFMElementTypes.STRIKETHROUGH })
    }

    @Test fun codeAndEscapedMarkersStayLiteral() {
        for (source in listOf(
            "`**验证限制：**本次`", "``**验证限制：**本次``",
            "```text\n**验证限制：**本次\n```", "~~~\n**验证限制：**本次\n~~~",
            "    **验证限制：**本次", "\\*\\*验证限制：\\*\\*本次",
        )) { assertStandard(source); assertTrue(source, strong(source).isEmpty()) }
    }

    @Test fun destinationsAutolinksAndHtmlAttributesAreNotRewritten() {
        for (source in listOf(
            "[link](https://example.com/**注意：**正文)", "![alt](https://example.com/**注意：**正文.png)",
            "<https://example.com/**注意：**正文>", "<span title=\"**注意：**正文\">text</span>",
            "[ref]: https://example.com/**注意：**正文\n\n[ref]",
        )) assertStandard(source)
    }

    @Test fun incompleteStreamingTextDoesNotInventClosingMarkers() {
        for (source in listOf("**验证限制：", "**验证限制：*", "**验证限制：**本次")) {
            assertEquals(if (source.endsWith("本次")) listOf("验证限制：") else emptyList<String>(), strong(source))
        }
    }

    @Test fun existingGfmAndWhitespaceRulesArePreserved() {
        for (source in listOf(
            "**English:**word", "word**(text)**word", "**普通中文**正文", "*斜体：*正文",
            "__下划线：__正文", "***粗斜体***", "**末尾空格： **正文", "** ：开头空格**",
            "**bold** *italic* ~~strike~~", "**标签：** **正文**", "***\n\n- [x] done",
        )) assertStandard(source)
    }

    @Test fun sourceOffsetsStillAddressOriginalMarkdownIncludingEmoji() {
        val source = "😀前文**“重点”：**正文🚀"
        val node = nodes(parse(source)).single { it.type == MarkdownElementTypes.STRONG }
        assertEquals("**“重点”：**", source.substring(node.startOffset, node.endOffset))
        assertEquals(source.indexOf("**"), node.startOffset)
    }
}
