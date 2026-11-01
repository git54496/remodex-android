package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.Input
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.remodex.android.ui.theme.Copper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import org.intellij.markdown.parser.MarkdownParser

/** Shared renderer for replies and expanded message details; the caller owns the bubble/card. */
@Composable
internal fun MarkdownBody(
    text: String,
    textColor: Color,
    bodyStyle: TextStyle,
    modifier: Modifier = Modifier,
    codeBlockBackground: Color = Color.Black.copy(alpha = 0.04f),
    monospaceByDefault: Boolean = false,
) {
    // Parse off the UI thread and retain the last result during streaming. Conflation skips
    // superseded chunks without repeatedly cancelling a slow parse and starving the display.
    val latestText by rememberUpdatedState(text)
    val parsed by produceState<State?>(initialValue = null) {
        snapshotFlow { latestText }.conflate().collect { content ->
            val flavour = CjkMarkdownFlavour()
            value = MarkdownState(Input(content, true, flavour, MarkdownParser(flavour), ReferenceLinkHandlerImpl())).parse()
        }
    }
    // Canonical history can replace the whole message; never render a stale unrelated result.
    val visible = (parsed as? State.Success)?.takeIf { text.startsWith(it.content) }
    val style = bodyStyle.copy(color = textColor,
        fontFamily = if (monospaceByDefault) FontFamily.Monospace else bodyStyle.fontFamily)
    if (visible == null) {
        // Cached conversations remain readable immediately, including on a parse failure.
        Text(text, modifier = modifier, style = style)
        return
    }
    val uriHandler = LocalUriHandler.current
    val safeUriHandler = remember(uriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // File links and custom schemes may have no installed Android handler.
                try { uriHandler.openUri(uri) } catch (_: IllegalArgumentException) { }
            }
        }
    }
    val heading = style.copy(fontWeight = FontWeight.SemiBold)
    CompositionLocalProvider(LocalUriHandler provides safeUriHandler) {
        Markdown(
            state = visible,
            modifier = modifier.testTag("rendered-markdown"),
            colors = markdownColor(text = textColor, codeBackground = codeBlockBackground,
                inlineCodeBackground = codeBlockBackground, dividerColor = textColor.copy(alpha = 0.18f),
                tableBackground = textColor.copy(alpha = 0.025f)),
            typography = markdownTypography(
                text = style, paragraph = style, ordered = style, bullet = style, list = style, table = style,
                h1 = heading.copy(fontSize = 22.sp, lineHeight = 28.sp),
                h2 = heading.copy(fontSize = 20.sp, lineHeight = 26.sp),
                h3 = heading.copy(fontSize = 18.sp), h4 = heading, h5 = heading, h6 = heading,
                code = style.copy(fontFamily = FontFamily.Monospace),
                inlineCode = style.copy(fontFamily = FontFamily.Monospace), quote = style,
                link = style.copy(color = Copper, textDecoration = TextDecoration.Underline),
            ),
            padding = markdownPadding(block = 8.dp, list = 0.dp, listItemTop = 2.dp,
                listItemBottom = 2.dp, listIndent = 8.dp, codeBlock = PaddingValues(12.dp)),
            dimens = markdownDimens(codeBackgroundCornerSize = 12.dp, tableCellPadding = 10.dp),
            animations = markdownAnimations(animateTextSize = { this }),
            imageTransformer = Coil2ImageTransformerImpl,
            components = markdownComponents(
                checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
                orderedList = { MarkdownOrderedList(it.content, it.node, it.typography.ordered, it.listDepth,
                    markerModifier = { Modifier.padding(end = 6.dp) }, listModifier = { Modifier.weight(1f) }) },
                unorderedList = { MarkdownBulletList(it.content, it.node, it.typography.bullet, it.listDepth,
                    markerModifier = { Modifier.padding(end = 6.dp) }, listModifier = { Modifier.weight(1f) }) },
                table = {
                    // The library scrolls wide tables horizontally. Allow cells to wrap instead
                    // of its default one-line ellipsis, so replies never lose table content.
                    MarkdownTable(it.content, it.node, it.typography.table,
                        headerBlock = { content, node, width, cellStyle ->
                            MarkdownTableHeader(content, node, width, cellStyle, maxLines = Int.MAX_VALUE)
                        },
                        rowBlock = { content, node, width, cellStyle ->
                            MarkdownTableRow(content, node, width, cellStyle, maxLines = Int.MAX_VALUE)
                        })
                },
            ),
        )
    }
}
