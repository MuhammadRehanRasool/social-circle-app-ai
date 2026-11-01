package dev.braindump.splitter.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.ui.theme.Glass

/**
 * Renders the Markdown Gemini Nano writes: headings, paragraphs, bullet, numbered and
 * checkbox lists, quotes, code blocks and rules, with bold, italic, strikethrough, inline
 * code and links inside them. Anything else shows as the plain text it is.
 */
@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val inlineColors = InlineColors(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = inline(block.text, inlineColors),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
                )

                is Block.Paragraph -> Text(inline(block.text, inlineColors), style = MaterialTheme.typography.bodyMedium)

                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item -> ListItemRow(item, inlineColors) }
                }

                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                    Text(
                        text = inline(block.text, inlineColors),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                is Block.Code -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(Glass.colors.inset)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )

                Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun ListItemRow(item: ListItem, colors: InlineColors) {
    val style: TextStyle = MaterialTheme.typography.bodyMedium
    Row(Modifier.padding(start = (item.depth * 16).dp)) {
        Text(
            text = item.marker,
            style = style,
            color = if (item.marker.length == 1) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.widthIn(min = 18.dp),
        )
        Text(
            text = inline(item.text, colors),
            style = style,
            textDecoration = if (item.marker == CHECKED) TextDecoration.LineThrough else null,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** One line of plain text from the note, for the collapsed card. */
internal fun markdownPreview(markdown: String): String =
    markdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("```") && !RULE.matches(it) }
        .map { line ->
            line.replace(LINE_PREFIX, "")
                .replace(LINK) { it.groupValues[1] }
                .replace(EMPHASIS_MARKS, "")
        }
        .joinToString(" ")

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class ListBlock(val items: List<ListItem>) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data object Rule : Block
}

private data class ListItem(val depth: Int, val marker: String, val text: String)

private fun parseBlocks(markdown: String): List<Block> {
    val blocks = ArrayList<Block>()
    val paragraph = ArrayList<String>()
    val items = ArrayList<ListItem>()
    val quote = ArrayList<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    fun flushList() {
        if (items.isNotEmpty()) blocks += Block.ListBlock(items.toList())
        items.clear()
    }
    fun flushQuote() {
        if (quote.isNotEmpty()) blocks += Block.Quote(quote.joinToString("\n"))
        quote.clear()
    }
    fun flushAll() {
        flushParagraph()
        flushList()
        flushQuote()
    }

    val lines = markdown.replace("\t", "    ").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        val heading = HEADING.matchEntire(trimmed)
        val listItem = LIST_ITEM.matchEntire(line)
        when {
            trimmed.startsWith("```") -> {
                flushAll()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                blocks += Block.Code(code.joinToString("\n"))
            }

            trimmed.isEmpty() -> flushAll()

            heading != null -> {
                flushAll()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }

            RULE.matches(trimmed) -> {
                flushAll()
                blocks += Block.Rule
            }

            listItem != null -> {
                flushParagraph()
                flushQuote()
                val (indent, bullet, check, text) = listItem.destructured
                val marker = when {
                    check.isNotEmpty() -> if (check.isBlank()) UNCHECKED else CHECKED
                    bullet.first().isDigit() -> bullet.dropLast(1) + "."
                    else -> if (indent.length >= 2) "◦" else "•"
                }
                items += ListItem(depth = (indent.length / 2).coerceAtMost(MAX_DEPTH), marker = marker, text = text)
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                flushList()
                quote += trimmed.removePrefix(">").trim()
            }

            // An indented line under a list item continues that item.
            items.isNotEmpty() && line.startsWith("  ") -> {
                val last = items.removeAt(items.lastIndex)
                items += last.copy(text = "${last.text} $trimmed")
            }

            else -> {
                flushList()
                flushQuote()
                paragraph += trimmed
            }
        }
        i++
    }
    flushAll()
    return blocks
}

private class InlineColors(val link: Color, val codeBackground: Color)

private fun inline(text: String, colors: InlineColors): AnnotatedString =
    buildAnnotatedString { appendInline(text, colors) }

private fun AnnotatedString.Builder.appendInline(text: String, colors: InlineColors) {
    var rest = text
    while (rest.isNotEmpty()) {
        val match = INLINE.find(rest)
        if (match == null) {
            append(rest)
            return
        }
        append(rest.substring(0, match.range.first))
        val groups = match.groups
        when {
            groups[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                append(groups[1]!!.value)
            }
            groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(groups[2]!!.value, colors) }
            groups[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(groups[3]!!.value, colors) }
            groups[4] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(groups[4]!!.value, colors) }
            groups[5] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(groups[5]!!.value, colors) }
            groups[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(groups[6]!!.value, colors) }
            else -> {
                val label = groups[7]!!.value
                val url = groups[8]!!.value
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val linkStyle = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
                } else {
                    append(label)
                }
            }
        }
        rest = rest.substring(match.range.last + 1)
    }
}

private const val CHECKED = "☑"
private const val UNCHECKED = "☐"
private const val MAX_DEPTH = 3

private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*$""")
private val RULE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
private val LIST_ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(?:\[([ xX])]\s+)?(.*)$""")
private val LINE_PREFIX = Regex("""^(?:#{1,6}\s+|>\s*|(?:[-*+]|\d+[.)])\s+(?:\[[ xX]]\s+)?)""")
private val LINK = Regex("""\[([^\]]+)]\([^)\s]+\)""")
private val EMPHASIS_MARKS = Regex("""\*\*|__|~~|`|(?<!\w)[*_](?=\S)|(?<=\S)[*_](?!\w)""")

// Order matters: at the same position, ** must win over *.
private val INLINE = Regex(
    """`([^`]+)`""" +
        """|\*\*(.+?)\*\*""" +
        """|__(.+?)__""" +
        """|~~(.+?)~~""" +
        """|\*(?=\S)(.+?)(?<=\S)\*""" +
        """|(?<!\w)_(?=\S)(.+?)(?<=\S)_(?!\w)""" +
        """|\[([^\]]+)]\(([^)\s]+)\)"""
)
