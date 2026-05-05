package com.opencode.remote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Markdown Data Model ──────────────────────────────────────────────────

internal sealed class MdSegment {
    data class CodeBlock(val language: String, val code: String) : MdSegment()
    data class Paragraph(val spans: List<MdSpan>) : MdSegment()
}

internal sealed class MdSpan {
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class InlineCode(val text: String) : MdSpan()
    data class Plain(val text: String) : MdSpan()
}

// ─── Markdown Parsing ─────────────────────────────────────────────────────

internal fun parseMarkdown(text: String): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block ```lang ... ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            segments.add(MdSegment.CodeBlock(lang, codeLines.joinToString("\n")))
            if (i < lines.size) i++ // skip closing ```
            continue
        }

        // Collect consecutive non-code-block lines as a paragraph
        val paraLines = mutableListOf<String>()
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            val joined = paraLines.joinToString("\n")
            segments.add(MdSegment.Paragraph(parseInlineSpans(joined)))
        }
    }

    return segments
}

internal fun parseInlineSpans(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val regex = Regex("""(\*\*(.+?)\*\*|`([^`]+)`|\*(.+?)\*)""")
    var lastEnd = 0

    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) {
            spans.add(MdSpan.Plain(text.substring(lastEnd, match.range.first)))
        }
        when {
            match.groupValues[2].isNotEmpty() -> spans.add(MdSpan.Bold(match.groupValues[2]))
            match.groupValues[3].isNotEmpty() -> spans.add(MdSpan.InlineCode(match.groupValues[3]))
            match.groupValues[4].isNotEmpty() -> spans.add(MdSpan.Italic(match.groupValues[4]))
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        spans.add(MdSpan.Plain(text.substring(lastEnd)))
    }
    if (spans.isEmpty()) {
        spans.add(MdSpan.Plain(text))
    }
    return spans
}

// ─── Markdown Text Composable ─────────────────────────────────────────────

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val segments = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MdSegment.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = codeBackground,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (segment.language.isNotEmpty()) {
                                Text(
                                    text = segment.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Box(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = segment.code,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        color = color,
                                    )
                                }
                            }
                        }
                    }
                }
                is MdSegment.Paragraph -> {
                    val annotated = buildAnnotatedString {
                        for (span in segment.spans) {
                            when (span) {
                                is MdSpan.Plain -> append(span.text)
                                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(span.text)
                                }
                                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                    append(span.text)
                                }
                                is MdSpan.InlineCode -> withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        background = codeBackground,
                                        fontSize = 13.sp,
                                    )
                                ) {
                                    append(" ${span.text} ")
                                }
                            }
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
