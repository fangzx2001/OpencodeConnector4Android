package com.opencode.remote.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessagePart
import com.opencode.remote.data.api.dto.MessageTokens
import com.opencode.remote.ui.strings.AppLocale

// ─── Message Segment Parsing ─────────────────────────────────────────────

/** Parse completed message parts into display segments. */
internal fun parseMessageSegments(message: MessageInfo): List<ResponseSegment> {
    return message.parts
        .filter { it.type in listOf("reasoning", "text", "tool-invocation", "tool-call", "tool", "step-finish") }
        .filter { part ->
            // Keep tool parts even if text is empty — they have structured data
            if (part.type == "tool" || part.type == "step-finish") true
            else !part.text.isNullOrBlank()
        }
        .map { part ->
            val segType = when (part.type) {
                "reasoning" -> "thinking"
                "text" -> "text"
                "step-finish" -> "step-finish"
                else -> if (part.tool == "task") "task" else "tool"
            }
            val toolSummary = if (part.type == "tool") ToolSummarizer.summarizeDetailed(part) else null
            val displayText = when (segType) {
                "step-finish" -> summarizeStepFinish(part)
                "tool", "task" -> toolSummary?.detail ?: part.text ?: ""
                else -> part.text ?: ""
            }
            ResponseSegment(
                type = segType,
                text = displayText,
                isStreaming = false,
                id = part.callID,
                label = toolSummary?.label,
                status = toolSummary?.status,
                toolName = toolSummary?.toolName,
                childSessionId = toolSummary?.childSessionId,
                title = toolSummary?.title,
                stepTokens = part.tokens,
                stepCost = part.cost,
                stepReason = part.reason,
            )
        }
}

private data class SegmentRenderItem(
    val textSegment: ResponseSegment? = null,
    val toolSegments: List<ResponseSegment> = emptyList(),
    val stepSegment: ResponseSegment? = null,
)

private fun groupSegmentsForRender(segments: List<ResponseSegment>): List<SegmentRenderItem> {
    val items = mutableListOf<SegmentRenderItem>()
    var toolBuffer = mutableListOf<ResponseSegment>()
    var pendingStep: ResponseSegment? = null

    fun flushTools() {
        if (toolBuffer.isNotEmpty() || pendingStep != null) {
            items += SegmentRenderItem(toolSegments = toolBuffer.toList(), stepSegment = pendingStep)
        }
        toolBuffer = mutableListOf()
        pendingStep = null
    }

    segments.forEach { segment ->
        when (segment.type) {
            "tool", "task" -> toolBuffer += segment
            "step-finish" -> {
                if (toolBuffer.isNotEmpty()) {
                    pendingStep = segment
                    flushTools()
                } else {
                    items += SegmentRenderItem(stepSegment = segment)
                }
            }
            else -> {
                flushTools()
                items += SegmentRenderItem(textSegment = segment)
            }
        }
    }

    flushTools()
    return items
}

internal fun summarizeStepFinish(part: MessagePart): String {
    val s = AppLocale.strings
    val bits = mutableListOf<String>()
    part.reason?.takeIf { it.isNotBlank() }?.let { bits += "${s.stepReasonLabel}: $it" }
    part.tokens?.let { bits += "${s.stepTokensLabel}: ${formatTokenCount(it)}" }
    part.cost?.let { bits += "${s.stepCostLabel}: ${"%.4f".format(it)}" }
    return bits.joinToString("\n")
}

private fun formatTokenCount(tokens: MessageTokens): String {
    val cacheRead = tokens.cache?.read ?: tokens.cacheRead ?: 0
    val cacheWrite = tokens.cache?.write ?: tokens.cacheWrite ?: 0
    val total = tokens.total ?: ((tokens.input ?: 0) + (tokens.output ?: 0) + (tokens.reasoning ?: 0) + cacheRead + cacheWrite)
    return if (total >= 1000) String.format("%.1fk", total / 1000f) else total.toString()
}

private data class ToolSummaryText(
    val text: String,
    val tone: ToolSummaryTone,
)

private enum class ToolSummaryTone {
    Normal,
    Active,
    Error,
}

private fun buildToolStepsSummary(segments: List<ResponseSegment>, strings: com.opencode.remote.ui.strings.AppStrings): List<ToolSummaryText> {
    if (segments.isEmpty()) return emptyList()

    val done = linkedMapOf<String, Int>()
    val failed = linkedMapOf<String, Int>()
    val active = linkedMapOf<String, Int>()

    segments.forEach { segment ->
        val category = segment.toolName.toToolSummaryCategory(strings)
        when {
            segment.status.equals("error", ignoreCase = true) || segment.status.equals("failed", ignoreCase = true) -> {
                failed[category] = (failed[category] ?: 0) + 1
            }
            segment.status.equals("running", ignoreCase = true) || segment.status.equals("pending", ignoreCase = true) || segment.isStreaming -> {
                active[category] = (active[category] ?: 0) + 1
            }
            else -> {
                done[category] = (done[category] ?: 0) + 1
            }
        }
    }

    val pieces = mutableListOf<ToolSummaryText>()
    fun appendGroup(map: LinkedHashMap<String, Int>, tone: ToolSummaryTone) {
        map.entries.forEachIndexed { index, entry ->
            if (pieces.isNotEmpty() && index == 0) {
                pieces += ToolSummaryText(" · ", ToolSummaryTone.Normal)
            } else if (index > 0) {
                pieces += ToolSummaryText(" · ", ToolSummaryTone.Normal)
            }
            pieces += ToolSummaryText(
                text = if (entry.value > 1) "${entry.key} ×${entry.value}" else entry.key,
                tone = tone,
            )
        }
    }

    appendGroup(done, ToolSummaryTone.Normal)
    appendGroup(failed, ToolSummaryTone.Error)
    appendGroup(active, ToolSummaryTone.Active)
    return if (pieces.isEmpty()) listOf(ToolSummaryText(strings.stepsLabel, ToolSummaryTone.Normal)) else pieces
}

private fun String?.toToolSummaryCategory(strings: com.opencode.remote.ui.strings.AppStrings): String {
    val lower = this?.lowercase().orEmpty()
    return when {
        lower.contains("todo") -> "Todo"
        lower == "task" -> strings.callChainLabel
        lower.contains("question") || lower.contains("ask") -> "Question"
        lower.contains("bash") || lower == "sh" || lower.contains("cmd") || lower.contains("terminal") || lower.contains("shell") -> strings.codeExecutionLabel
        lower.contains("write") || lower.contains("save") -> "Write"
        lower.contains("edit") || lower.contains("replace") || lower.contains("patch") -> "Edit"
        lower.contains("read") || lower.contains("cat") -> "Read"
        lower.contains("grep") || lower.contains("search") -> "Search"
        lower.contains("glob") || lower.contains("find") -> "List"
        lower.contains("fetch") || lower.contains("http") || lower.contains("web") || lower.contains("browse") || lower.contains("network") -> "Network"
        lower.contains("think") || lower.contains("reason") || lower.contains("plan") -> strings.reasoningLabel
        else -> this ?: strings.toolActivityLabel
    }
}

// ─── User Message Item ────────────────────────────────────────────────────

@Composable
internal fun UserMessageItem(message: MessageInfo) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val s = AppLocale.strings
            Text(
                s.me,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            message.parts
                .filter { it.type == "text" && !it.text.isNullOrBlank() }
                .forEach { part ->
                    SelectionContainer {
                        Text(
                            text = part.text!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
        }
    }
}

// ─── AI Response Panel ────────────────────────────────────────────────────

@Composable
internal fun AiResponsePanel(
    agentName: String,
    segments: List<ResponseSegment>,
    isStreaming: Boolean,
) {
    val s = AppLocale.strings
    val renderItems = remember(segments) { groupSegmentsForRender(segments) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))

            renderItems.forEachIndexed { idx, item ->
                val isLastItem = idx == renderItems.lastIndex
                when {
                    item.textSegment?.type == "thinking" -> {
                        val segment = item.textSegment
                        val isActive = segment?.isStreaming == true && isLastItem && isStreaming
                        StatusSegmentCard(
                            text = segment?.text.orEmpty(),
                            isStreaming = isActive,
                            label = if (isActive) s.thinkingActive else s.thought,
                            icon = Icons.Default.Psychology,
                            status = if (isActive) s.statusRunning else s.statusCompleted,
                            category = s.reasoningLabel,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    item.textSegment?.type == "step-finish" -> StepFinishCard(item.textSegment)
                    item.textSegment != null -> MessageTextBubble(item.textSegment.text)
                    else -> ToolStepGroupCard(
                        segments = item.toolSegments,
                        stepSegment = item.stepSegment,
                        isStreaming = isStreaming && isLastItem,
                    )
                }
                if (!isLastItem) Spacer(Modifier.height(6.dp))
            }

            if (isStreaming) {
                val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(530, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .width(8.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)),
                )
            }
        }
    }
}

@Composable
private fun MessageTextBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ToolStepGroupCard(
    segments: List<ResponseSegment>,
    stepSegment: ResponseSegment?,
    isStreaming: Boolean,
) {
    val s = AppLocale.strings
    val doneCount = segments.count {
        !it.status.equals("error", ignoreCase = true) &&
            !it.status.equals("failed", ignoreCase = true) &&
            !it.status.equals("running", ignoreCase = true) &&
            !it.status.equals("pending", ignoreCase = true) &&
            !it.isStreaming
    }
    val hasActive = isStreaming || segments.any {
        it.status.equals("running", ignoreCase = true) || it.status.equals("pending", ignoreCase = true)
    }
    val hasFailed = segments.any {
        it.status.equals("error", ignoreCase = true) || it.status.equals("failed", ignoreCase = true)
    }
    val headerSummary = remember(segments) { buildToolStepsSummary(segments, s) }
    val collapsedTokenPreview = stepSegment?.stepTokens?.let { formatTokenCount(it) }
    var expanded by remember(isStreaming, segments.size) { mutableStateOf(isStreaming || segments.size <= 1) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (hasActive) Icons.Default.PlayArrow else Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.stepsLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                    if (expanded) {
                        Text(
                            text = s.stepsCount.replace("%1\$d", doneCount.toString()).replace("%2\$d", segments.size.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            headerSummary.forEach { piece ->
                                Text(
                                    text = piece.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (piece.tone) {
                                        ToolSummaryTone.Normal -> MaterialTheme.colorScheme.onSecondaryContainer
                                        ToolSummaryTone.Active -> MaterialTheme.colorScheme.primary
                                        ToolSummaryTone.Error -> MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = if (piece.tone == ToolSummaryTone.Normal) FontWeight.Medium else FontWeight.SemiBold,
                                )
                            }
                            if (!collapsedTokenPreview.isNullOrBlank()) {
                                Text(
                                    text = "  ·  $collapsedTokenPreview ${s.stepTokensLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.68f),
                                )
                            }
                        }
                    }
                }
                StatusBadge(
                    status = when {
                        hasActive -> s.statusRunning
                        hasFailed -> s.statusFailed
                        else -> s.statusCompleted
                    },
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    segments.forEachIndexed { index, segment ->
                        ToolExecutionStepRow(index = index + 1, segment = segment, isActive = isStreaming && index == segments.lastIndex)
                    }
                }
            }

            if (stepSegment != null) {
                Spacer(Modifier.height(10.dp))
                StepFinishCard(stepSegment)
            }
        }
    }
}

@Composable
private fun ToolExecutionStepRow(index: Int, segment: ResponseSegment, isActive: Boolean) {
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val failed = segment.status.equals("error", ignoreCase = true) || segment.status.equals("failed", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                RoundedCornerShape(14.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(22.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = index.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.title ?: segment.label ?: ToolSummarizer.summarizeText(segment.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(status = segment.status.toDisplayStatus(AppLocale.strings, isActive), contentColor = contentColor)
            }
            Spacer(Modifier.height(8.dp))
            MarkdownText(text = segment.text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StepFinishCard(segment: ResponseSegment) {
    val s = AppLocale.strings
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = s.stepFinishedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (segment.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

// ─── Expandable Segment (for thinking / tool) ─────────────────────────────

@Composable
internal fun ExpandableSegment(
    text: String,
    isStreaming: Boolean,
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
) {
    val s = AppLocale.strings
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = 0.6f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = containerColor.copy(alpha = 0.6f),
                    ) {
                        Box(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = contentColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(
    segment: ResponseSegment,
    isActive: Boolean,
) {
    val s = AppLocale.strings
    val category = when (segment.toolName) {
        "bash" -> s.codeExecutionLabel
        else -> s.toolActivityLabel
    }
    StatusSegmentCard(
        text = segment.text,
        isStreaming = isActive,
        label = segment.label ?: ToolSummarizer.summarizeText(segment.text),
        icon = if (segment.toolName == "bash") Icons.Default.PlayArrow else Icons.Default.Build,
        status = segment.status.toDisplayStatus(s, isActive),
        category = category,
        title = segment.title,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun TaskChainCard(
    segment: ResponseSegment,
    isActive: Boolean,
) {
    val s = AppLocale.strings
    val childSessionLine = segment.childSessionId?.let { "${s.childSessionLabel}\n$it" }
    val detail = listOfNotNull(segment.text.takeIf { it.isNotBlank() }, childSessionLine)
        .joinToString("\n\n")
    StatusSegmentCard(
        text = detail,
        isStreaming = isActive,
        label = segment.label ?: s.callChainLabel,
        icon = Icons.Default.Hub,
        status = segment.status.toDisplayStatus(s, isActive),
        category = s.callChainLabel,
        title = segment.title,
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun StatusSegmentCard(
    text: String,
    isStreaming: Boolean,
    label: String,
    icon: ImageVector,
    status: String,
    category: String,
    containerColor: Color,
    contentColor: Color,
    title: String? = null,
) {
    val s = AppLocale.strings
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                    )
                    Text(
                        text = title ?: label,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(status = status, contentColor = contentColor)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = 0.6f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    HorizontalDivider(color = contentColor.copy(alpha = 0.18f))
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = containerColor.copy(alpha = 0.68f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = contentColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, contentColor: Color) {
    val badgeColor = when {
        status.contains("error", ignoreCase = true) || status.contains("failed", ignoreCase = true) -> MaterialTheme.colorScheme.errorContainer
        status.contains("running", ignoreCase = true) -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)
    }
    val badgeContentColor = when {
        status.contains("error", ignoreCase = true) || status.contains("failed", ignoreCase = true) -> MaterialTheme.colorScheme.onErrorContainer
        else -> contentColor
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = badgeColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (status.contains("error", ignoreCase = true) || status.contains("failed", ignoreCase = true)) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = badgeContentColor,
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = badgeContentColor,
            )
        }
    }
}

private fun String?.toDisplayStatus(strings: com.opencode.remote.ui.strings.AppStrings, isStreaming: Boolean): String {
    return when {
        this.equals("completed", ignoreCase = true) -> strings.statusCompleted
        this.equals("running", ignoreCase = true) -> strings.statusRunning
        this.equals("pending", ignoreCase = true) -> strings.statusPending
        this.equals("error", ignoreCase = true) || this.equals("failed", ignoreCase = true) -> strings.statusFailed
        isStreaming -> strings.statusRunning
        else -> strings.statusCompleted
    }
}

// ─── Chat Input Bar ───────────────────────────────────────────────────────

@Composable
internal fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    val s = AppLocale.strings
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                placeholder = { Text(s.inputPlaceholder) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (inputText.isNotBlank()) {
                        Box(
                            modifier = Modifier.offset(x = (-6).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onInputChange("") },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = s.close,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )

            FilledIconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isSending,
                modifier = Modifier.size(48.dp),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = s.connectButton)
                }
            }
        }
    }
}
