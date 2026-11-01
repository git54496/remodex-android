package dev.remodex.android.feature.threads

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

/** GFM with support for unspaced Chinese strong text, e.g. **注意：**正文. */
internal class CjkMarkdownFlavour : GFMFlavourDescriptor() {
    private val gfmParsers = super.sequentialParserManager
    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> =
            gfmParsers.getParserSequence().map { parser ->
                if (parser is EmphasisLikeParser) {
                    EmphasisLikeParser(CjkStrongDelimiterParser(), StrikeThroughDelimiterParser())
                } else parser
            }
    }
}

private class CjkStrongDelimiterParser : DelimiterParser() {
    private val standard = EmphStrongDelimiterParser()

    override fun scan(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>): Int {
        val first = delimiters.size
        val steps = standard.scan(tokens, iterator, delimiters)
        // Adjust only ** runs. The library still handles escapes, pairing, nesting, and
        // unmatched delimiters. Its earlier parsers exclude code and link destinations.
        if (steps == 2 && iterator.firstChar == '*') {
            val before = iterator.charLookup(-1)
            val after = tokens.getRawCharAt(iterator.start + 2)
            for (index in first until delimiters.size) {
                if (isCjkPunctuation(after)) delimiters[index].canOpen = true
                if (isCjkPunctuation(before)) delimiters[index].canClose = true
            }
        }
        return steps
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) = standard.process(tokens, iterator, delimiters, result)

    private fun isCjkPunctuation(char: Char): Boolean {
        val punctuation = when (Character.getType(char)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
        if (!punctuation) return false
        return when (Character.UnicodeBlock.of(char)) {
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS,
            Character.UnicodeBlock.VERTICAL_FORMS -> true
            // Chinese prose also commonly uses these Unicode quotes, dashes, and ellipsis.
            else -> char in '\u2018'..'\u201f' || char == '—' || char == '…'
        }
    }
}
