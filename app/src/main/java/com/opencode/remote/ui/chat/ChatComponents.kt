@file:Suppress("DEPRECATION")
package com.opencode.remote.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.GTranslate
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessagePart
import com.opencode.remote.data.api.dto.MessageTokens
import com.opencode.remote.ui.strings.AppLocale
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

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
                inputText = toolSummary?.inputText,
                outputText = toolSummary?.outputText,
                metadataText = toolSummary?.metadataText,
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
    return bits.joinToString("  ·  ")
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

private data class DetailSection(
    val label: String,
    val text: String,
)

private fun buildDetailSections(segment: ResponseSegment, strings: com.opencode.remote.ui.strings.AppStrings): List<DetailSection> {
    val sections = mutableListOf<DetailSection>()
    val executionText = buildList {
        segment.title?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (segment.inputText.isNullOrBlank() && segment.outputText.isNullOrBlank() && segment.metadataText.isNullOrBlank()) {
            segment.text.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        segment.childSessionId?.takeIf { it.isNotBlank() }?.let { add("${strings.childSessionLabel}: $it") }
    }.joinToString("\n\n")

    executionText.takeIf { it.isNotBlank() }?.let {
        sections += DetailSection(strings.stepExecutionLabel, it)
    }
    segment.inputText?.takeIf { it.isNotBlank() }?.let {
        sections += DetailSection(strings.stepInputLabel, it)
    }
    segment.outputText?.takeIf { it.isNotBlank() }?.let {
        sections += DetailSection(strings.stepOutputLabel, it)
    }
    segment.metadataText?.takeIf { it.isNotBlank() }?.let {
        sections += DetailSection(strings.stepMetadataLabel, it)
    }
    return sections
}

private fun copyRawFields(context: Context, label: String, content: String, copiedMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
}

private fun buildSegmentRawFields(segment: ResponseSegment): String {
    return buildString {
        appendLine("type: ${segment.type}")
        appendLine("id: ${segment.id.orEmpty()}")
        appendLine("label: ${segment.label.orEmpty()}")
        appendLine("status: ${segment.status.orEmpty()}")
        appendLine("toolName: ${segment.toolName.orEmpty()}")
        appendLine("title: ${segment.title.orEmpty()}")
        appendLine("childSessionId: ${segment.childSessionId.orEmpty()}")
        appendLine("stepReason: ${segment.stepReason.orEmpty()}")
        appendLine("stepTokens: ${segment.stepTokens?.toString().orEmpty()}")
        appendLine("stepCost: ${segment.stepCost?.toString().orEmpty()}")
        appendLine("inputText:")
        appendLine(segment.inputText.orEmpty())
        appendLine("outputText:")
        appendLine(segment.outputText.orEmpty())
        appendLine("metadataText:")
        appendLine(segment.metadataText.orEmpty())
        appendLine("text:")
        append(segment.text)
    }.trimEnd()
}

private fun buildGroupRawFields(segments: List<ResponseSegment>, stepSegment: ResponseSegment?): String {
    return buildString {
        appendLine("groupSegmentCount: ${segments.size}")
        segments.forEachIndexed { index, segment ->
            appendLine()
            appendLine("segment[$index]")
            appendLine(buildSegmentRawFields(segment))
        }
        if (stepSegment != null) {
            appendLine()
            appendLine("stepFinish")
            append(buildSegmentRawFields(stepSegment))
        }
    }.trimEnd()
}

private object ThinkingTranslationClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    suspend fun translateToZh(text: String): String {
        val response: JsonElement = client.post("https://translate.googleapis.com/translate_a/single") {
            parameter("client", "gtx")
            parameter("sl", "auto")
            parameter("tl", "zh-CN")
            parameter("dt", "t")
            parameter("q", text)
            contentType(ContentType.Application.Json)
        }.body()
        return extractTranslation(response)
    }

    private fun extractTranslation(element: JsonElement): String {
        val top = element as? JsonArray ?: return ""
        val lines = top.firstOrNull() as? JsonArray ?: return ""
        return lines.mapNotNull { row ->
            val arr = row as? JsonArray ?: return@mapNotNull null
            (arr.firstOrNull() as? JsonPrimitive)?.content
        }.joinToString("")
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
    messageKey: String,
    agentName: String,
    segments: List<ResponseSegment>,
    isStreaming: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    status = if (isStreaming) s.statusRunning else s.statusCompleted,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onExpandedChange(!expanded) },
                )
            }

            val summaryLine = remember(renderItems) {
                buildList {
                    val thinkingCount = renderItems.count { it.textSegment?.type == "thinking" }
                    val stepCount = renderItems.sumOf { it.toolSegments.size }
                    val outputCount = renderItems.count { it.textSegment?.type == "text" }
                    if (thinkingCount > 0) add("${s.reasoningLabel} ×$thinkingCount")
                    if (stepCount > 0) add("${s.toolActivityLabel} ×$stepCount")
                    if (outputCount > 0) add("Output ×$outputCount")
                }.joinToString("  ·  ")
            }

            if (summaryLine.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = summaryLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    renderItems.forEachIndexed { idx, item ->
                        val isLastItem = idx == renderItems.lastIndex
                        when {
                            item.textSegment?.type == "thinking" -> {
                                val segment = item.textSegment
                                val isActive = segment.isStreaming && isLastItem && isStreaming
                                ThinkingSegmentCard(
                                    text = segment.text,
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
                            item.toolSegments.isEmpty() && item.stepSegment != null -> StepFinishCard(item.stepSegment)
                            item.textSegment != null -> MessageTextBubble(item.textSegment.text)
                            else -> ToolStepGroupCard(
                                groupKey = "$messageKey-group-$idx-${item.toolSegments.firstOrNull()?.id.orEmpty()}",
                                segments = item.toolSegments,
                                stepSegment = item.stepSegment,
                                isStreaming = isStreaming && isLastItem,
                            )
                        }
                        if (!isLastItem) Spacer(Modifier.height(6.dp))
                    }
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageTextBubble(text: String) {
    val context = LocalContext.current
    val s = AppLocale.strings
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    copyRawFields(context, "message-text", text, s.rawFieldsCopied)
                },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ThinkingSegmentCard(
    text: String,
    isStreaming: Boolean,
    label: String,
    icon: ImageVector,
    status: String,
    category: String,
    containerColor: Color,
    contentColor: Color,
) {
    val context = LocalContext.current
    val s = AppLocale.strings
    val scope = rememberCoroutineScope()
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }
    var translatedText by remember(text) { mutableStateOf<String?>(null) }
    var isTranslating by remember(text) { mutableStateOf(false) }

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
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        if (translatedText != null) {
                            translatedText = null
                            return@IconButton
                        }
                        if (isTranslating || text.isBlank()) return@IconButton
                        isTranslating = true
                        scope.launch {
                            try {
                                translatedText = ThinkingTranslationClient.translateToZh(text).takeIf { it.isNotBlank() }
                                    ?: translatedText
                            } catch (_: Exception) {
                                Toast.makeText(context, s.translateFailed, Toast.LENGTH_SHORT).show()
                            } finally {
                                isTranslating = false
                            }
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GTranslate,
                            contentDescription = if (translatedText == null) "Translate" else "Restore",
                            modifier = Modifier.size(16.dp),
                            tint = if (translatedText == null) contentColor else MaterialTheme.colorScheme.primary,
                        )
                    }
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
                                    text = translatedText ?: text,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolStepGroupCard(
    groupKey: String,
    segments: List<ResponseSegment>,
    stepSegment: ResponseSegment?,
    isStreaming: Boolean,
) {
    val context = LocalContext.current
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
    var expanded by rememberSaveable(groupKey) { mutableStateOf(isStreaming || segments.size <= 1) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        copyRawFields(
                            context = context,
                            label = "tool-step-group",
                            content = buildGroupRawFields(segments, stepSegment),
                            copiedMessage = s.rawFieldsCopied,
                        )
                    },
                ),
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
                        ToolExecutionStepRow(
                            stepKey = "$groupKey-step-${segment.id ?: index}",
                            index = index + 1,
                            segment = segment,
                            isActive = isStreaming && index == segments.lastIndex,
                        )
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
private fun ToolExecutionStepRow(stepKey: String, index: Int, segment: ResponseSegment, isActive: Boolean) {
    val s = AppLocale.strings
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val failed = segment.status.equals("error", ignoreCase = true) || segment.status.equals("failed", ignoreCase = true)
    val detailSections = remember(segment) { buildDetailSections(segment, s) }
    var expanded by rememberSaveable(stepKey) { mutableStateOf(isActive || detailSections.size <= 1) }
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
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { expanded = !expanded },
                    tint = contentColor.copy(alpha = 0.72f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    if (detailSections.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        detailSections.forEach { section ->
                            DetailSectionCard(
                                sectionKey = "$stepKey-${section.label}",
                                label = section.label,
                                text = section.text,
                                accentColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailSectionCard(
    sectionKey: String,
    label: String,
    text: String,
    accentColor: Color,
) {
    val context = LocalContext.current
    val s = AppLocale.strings
    var expanded by rememberSaveable(sectionKey) { mutableStateOf(label == s.stepExecutionLabel) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = {
                            copyRawFields(
                                context = context,
                                label = "tool-section-$label",
                                content = text,
                                copiedMessage = s.rawFieldsCopied,
                            )
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    FormattedToolContent(
                        label = label,
                        text = text,
                        accentColor = accentColor,
                    )
                }
            }
        }
    }
}

private data class KeyValueLine(
    val key: String,
    val value: String,
)

private fun parseKeyValueLines(text: String): List<KeyValueLine> {
    return text.lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0 || idx == line.lastIndex) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isBlank() || value.isBlank()) null else KeyValueLine(key, value)
        }
}

private fun looksLikeShellCommand(text: String): Boolean {
    val single = text.trim()
    if (single.isBlank()) return false
    val prefixes = listOf(
        "git ", "npm ", "pnpm ", "yarn ", "bun ", "python ", "python3 ", "pip ", "pip3 ",
        "bash ", "sh ", "adb ", "gradlew", "./gradlew", "ls ", "cp ", "mv ", "rm ", "mkdir ",
        "cat ", "curl ", "wget ", "gh ", "java ", "javac ", "node ", "npx ", "go ", "cargo ",
        "kubectl ", "docker ", "fastboot ", "opencode ", "cd "
    )
    return prefixes.any { single.startsWith(it) }
}

private fun looksLikeCodeBlock(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("```") ||
        trimmed.contains("\nfun ") ||
        trimmed.contains("\nclass ") ||
        trimmed.contains("\nif (") ||
        trimmed.contains("\n{") ||
        trimmed.contains("</") ||
        trimmed.contains("import ")
}

@Composable
private fun FormattedToolContent(
    label: String,
    text: String,
    accentColor: Color,
) {
    val s = AppLocale.strings
    val keyValues = remember(text) { parseKeyValueLines(text) }
    when {
        keyValues.isNotEmpty() && keyValues.size == text.lines().count { it.isNotBlank() } -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                keyValues.forEach { item ->
                    KeyValueRow(item = item, accentColor = accentColor)
                }
            }
        }
        label == s.stepOutputLabel && looksLikeShellCommand(text) -> {
            CodeLikeBlock(text = text, accentColor = accentColor, language = "shell")
        }
        label == s.stepExecutionLabel && looksLikeShellCommand(text.lineSequence().lastOrNull().orEmpty()) -> {
            MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
        }
        looksLikeCodeBlock(text) -> {
            val normalized = if (text.trimStart().startsWith("```")) text else "```\n$text\n```"
            MarkdownText(text = normalized, color = MaterialTheme.colorScheme.onSurface)
        }
        else -> {
            MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun KeyValueRow(
    item: KeyValueLine,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.key,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        if (looksLikeShellCommand(item.value)) {
            CodeLikeBlock(
                text = item.value,
                accentColor = accentColor,
                language = "shell",
                modifier = Modifier.weight(1f),
            )
        } else if (looksLikeCodeBlock(item.value)) {
            CodeLikeBlock(
                text = item.value,
                accentColor = accentColor,
                language = null,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CodeLikeBlock(
    text: String,
    accentColor: Color,
    language: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            language?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
            }
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepFinishCard(segment: ResponseSegment) {
    val context = LocalContext.current
    val s = AppLocale.strings
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    copyRawFields(
                        context = context,
                        label = "step-finish",
                        content = buildSegmentRawFields(segment),
                        copiedMessage = s.rawFieldsCopied,
                    )
                },
            ),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
