package dev.remodex.android.feature.threads

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class ListGroup(val items: List<ListItem>) : MarkdownBlock
    data class ListItem(val marker: String, val text: String, val indent: Int)
}

/** Keeps list markers outside prose so wrapped lines align with the text, not the bullet. */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val items = mutableListOf<MarkdownBlock.ListItem>()
    val listPattern = Regex("^( *)([-+*]|[0-9]{1,9}[.)]) +(.+)$")
    val headingPattern = Regex("^(#{1,6}) +(.+)$")
    var fence: String? = null
    val code = mutableListOf<String>()
    var blankAfterItem = false
    fun flushParagraph() { if (paragraph.isNotEmpty()) { blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n")); paragraph.clear() } }
    fun flushList() { if (items.isNotEmpty()) { blocks += MarkdownBlock.ListGroup(items.toList()); items.clear() }; blankAfterItem = false }
    for (line in text.replace("\r\n", "\n").split('\n')) {
        val trimmed = line.trimStart()
        val activeFence = fence
        if (activeFence != null) {
            if (trimmed.length >= activeFence.length && trimmed.all { it == activeFence.first() }) {
                blocks += MarkdownBlock.Code(code.joinToString("\n")); code.clear(); fence = null
            } else code += line
            continue
        }
        val opening = Regex("^(`{3,}|~{3,}).*$").matchEntire(trimmed)
        if (opening != null) {
            flushParagraph(); flushList(); fence = opening.groupValues[1]; continue
        }
        if (line.isBlank()) { flushParagraph(); blankAfterItem = items.isNotEmpty(); continue }
        val heading = headingPattern.matchEntire(line)
        val list = listPattern.matchEntire(line)
        when {
            heading != null -> { flushParagraph(); flushList(); blocks += MarkdownBlock.Heading(heading.groupValues[2], heading.groupValues[1].length) }
            list != null -> {
                flushParagraph()
                val marker = list.groupValues[2]
                items += MarkdownBlock.ListItem(if (marker in listOf("-", "+", "*")) "•" else marker, list.groupValues[3], list.groupValues[1].length)
                blankAfterItem = false
            }
            items.isNotEmpty() && (!blankAfterItem || line.length - trimmed.length > items.last().indent) -> {
                val last = items.last()
                items[items.lastIndex] = last.copy(text = last.text + (if (blankAfterItem) "\n\n" else "\n") + trimmed)
                blankAfterItem = false
            }
            else -> { flushList(); paragraph += line }
        }
    }
    if (fence != null) blocks += MarkdownBlock.Code(code.joinToString("\n"))
    flushParagraph(); flushList()
    return blocks
}
