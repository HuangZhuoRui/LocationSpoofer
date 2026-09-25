package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue

sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String, val category: String) : MarkdownBlock()
    data class ListItem(val text: String, val bulletType: String = "bullet") : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String = "") : MarkdownBlock()
}

fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    if (rawText.isBlank()) return emptyList()
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    var inCodeBlock = false
    val codeLines = mutableListOf<String>()

    for (rawLine in lines) {
        val line = rawLine.trim()

        if (line.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
                codeLines.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeLines.add(rawLine)
            continue
        }

        if (line.isEmpty()) continue

        val headerMatch = Regex("""^(#{1,6})\s+(.*)""").matchEntire(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val headerText = headerMatch.groupValues[2].trim()
            val lower = headerText.lowercase()
            val category = when {
                lower.contains("功能") || lower.contains("feature") || lower.contains("新增") || lower.contains(
                    "优化"
                ) || lower.contains("ميزة") || lower.contains("تحسين") -> "feature"

                lower.contains("修复") || lower.contains("fix") || lower.contains("bug") || lower.contains(
                    "解决"
                ) || lower.contains("إصلاح") || lower.contains("حل") -> "fix"

                else -> "other"
            }
            if (headerText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Header(level, headerText, category))
            }
            continue
        }

        if (line.startsWith(">")) {
            val quoteText = line.removePrefix(">").trim()
            if (quoteText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Blockquote(quoteText))
            }
            continue
        }

        val isBullet =
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || line.startsWith(
                "• "
            )
        val isNumbered = line.matches(Regex("""^\d+[\.\)]\s+.*"""))

        if (isBullet) {
            val itemText = line.substring(2).trim()
            if (itemText.isNotEmpty()) {
                blocks.add(MarkdownBlock.ListItem(itemText, "bullet"))
            }
            continue
        }

        if (isNumbered) {
            val itemText = line.replace(Regex("""^\d+[\.\)]\s*"""), "").trim()
            if (itemText.isNotEmpty()) {
                blocks.add(MarkdownBlock.ListItem(itemText, "number"))
            }
            continue
        }

        // 普通文本段落
        blocks.add(MarkdownBlock.Paragraph(line))
    }

    if (inCodeBlock && codeLines.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
    }

    return blocks
}

@Composable
fun RenderMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = block.text.replace(Regex("""^[#🌟🛠📝\s]+"""), "").trim(),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp, end = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(AccentBlue)
                        )
                        Text(
                            text = parseInlineMarkdownString(block.text),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdownString(block.text),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                is MarkdownBlock.Blockquote -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(18.dp)
                                    .background(AccentBlue, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = parseInlineMarkdownString(block.text),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = AccentBlue,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

fun parseInlineMarkdownString(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val bold1 = text.indexOf("**", i)
            val bold2 = text.indexOf("__", i)
            val code = text.indexOf("`", i)
            val link = text.indexOf("[", i)
            val italic = text.indexOf("*", i)

            var minIdx = Int.MAX_VALUE
            var tokenType = ""

            if (bold1 in i until minIdx) {
                minIdx = bold1; tokenType = "bold1"
            }
            if (bold2 in i until minIdx) {
                minIdx = bold2; tokenType = "bold2"
            }
            if (code in i until minIdx) {
                minIdx = code; tokenType = "code"
            }
            if (link in i until minIdx) {
                minIdx = link; tokenType = "link"
            }
            if (italic in i until minIdx && italic != bold1) {
                minIdx = italic; tokenType = "italic"
            }

            if (minIdx == Int.MAX_VALUE) {
                append(text.substring(i))
                break
            }

            if (minIdx > i) {
                append(text.substring(i, minIdx))
            }

            i = minIdx
            var parsed = false

            when (tokenType) {
                "bold1", "bold2" -> {
                    val delim = if (tokenType == "bold1") "**" else "__"
                    val end = text.indexOf(delim, i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                        parsed = true
                    }
                }

                "italic" -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                        parsed = true
                    }
                }

                "code" -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = AccentBlue
                            )
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                        parsed = true
                    }
                }

                "link" -> {
                    val closeBracket = text.indexOf("]", i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(")", closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val linkUrl = text.substring(closeBracket + 2, closeParen)
                            withStyle(
                                SpanStyle(
                                    color = AccentBlue,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                pushStringAnnotation(tag = "URL", annotation = linkUrl)
                                append(linkText)
                                pop()
                            }
                            i = closeParen + 1
                            parsed = true
                        }
                    }
                }
            }

            if (!parsed) {
                append(text[i])
                i++
            }
        }
    }
}

@Composable
fun parseMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return parseInlineMarkdownString(text)
}
