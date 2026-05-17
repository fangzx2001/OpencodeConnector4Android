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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessagePart
import com.opencode.remote.data.api.dto.ModelInfo
import com.opencode.remote.ui.strings.AppLocale
import kotlinx.coroutines.delay

// ─── Message Segment Parsing ─────────────────────────────────────────────

/** Parse completed message parts into display segments. */
internal fun parseMessageSegments(message: MessageInfo): List<ResponseSegment> {
    return message.parts
        .filter { it.type in listOf("reasoning", "text", "tool-invocation", "tool-call", "tool") }
        .filter { part ->
            // Keep tool parts even if text is empty — they have structured data
            if (part.type == "tool") true
            else !part.text.isNullOrBlank()
        }
        .map { part ->
            val segType = when (part.type) {
                "reasoning" -> "thinking"
                "text" -> "text"
                else -> "tool"
            }
            val displayText = when (segType) {
                "tool" -> ToolSummarizer.summarize(part)
                else -> part.text ?: ""
            }
            ResponseSegment(type = segType, text = displayText, isStreaming = false)
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

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                agentName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))

            segments.forEachIndexed { idx, seg ->
                val isLast = idx == segments.lastIndex
                when (seg.type) {
                    "thinking" -> {
                        val isActive = seg.isStreaming && isLast && isStreaming
                        ExpandableSegment(
                            text = seg.text,
                            isStreaming = isActive,
                            label = if (isActive) s.thinkingActive else s.thought,
                            icon = Icons.Default.Psychology,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            showDuration = true,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    "tool" -> {
                        ExpandableSegment(
                            text = seg.text,
                            isStreaming = seg.isStreaming && isLast,
                            label = ToolSummarizer.summarizeText(seg.text),
                            icon = Icons.Default.Build,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    else -> {
                        MarkdownText(
                            text = seg.text,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
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
                        .width(8.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)),
                )
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
    showDuration: Boolean = false,
) {
    val s = AppLocale.strings
    // Default collapsed for all segments. Streaming segments auto-expand via
    // LaunchedEffect(isStreaming) below. The "disappear" bug is handled by the
    // ViewModel keeping streaming segments visible until the message is confirmed.
    var expanded by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf<Long?>(null) }
    var durationSec by remember { mutableStateOf<Int?>(null) }

    // Auto-expand on stream start, auto-collapse with delay on stream end
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            expanded = true
            if (startTime == null) startTime = System.currentTimeMillis()
        } else {
            if (startTime != null) {
                durationSec = ((System.currentTimeMillis() - startTime!!) / 1000).toInt()
                startTime = null
            }
            // Don't auto-collapse on stream end — keep content visible.
            // The user can manually collapse if they want.
        }
    }

    val displayLabel = when {
        !showDuration -> label
        isStreaming -> label  // "Thinking..." passed from caller
        durationSec != null && durationSec!! > 0 -> s.thoughtForSeconds.replace("%d", durationSec.toString())
        else -> label  // "Thought" passed from caller
    }

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
                    text = displayLabel,
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

// ─── Chat Input Bar ───────────────────────────────────────────────────────

@Composable
internal fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    selectedModel: ModelInfo?,
    availableModels: List<ModelInfo>,
    onSelectModel: (ModelInfo) -> Unit,
    isLoadingModels: Boolean,
    contextUsageK: String,
    onScrollToBottom: () -> Unit = {},
) {
    val s = AppLocale.strings
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    // Track press state for middle piano key (model selector)
    val middleKeyInteractionSource = remember { MutableInteractionSource() }
    val middleKeyPressed by middleKeyInteractionSource.interactions.collectAsState(
        initial = null
    )
    val isMiddleKeyPressed = middleKeyPressed is PressInteraction.Press

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // ── Piano-key strip (top row) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left key — scroll to bottom
                val leftKeyInteractionSource = remember { MutableInteractionSource() }
                val leftKeyPressed by leftKeyInteractionSource.interactions.collectAsState(
                    initial = null
                )
                val isLeftKeyPressed = leftKeyPressed is PressInteraction.Press
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp)
                        .clickable(
                            interactionSource = leftKeyInteractionSource,
                            indication = null,
                        ) { onScrollToBottom() }
                        .background(
                            if (isLeftKeyPressed) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Divider 1
                HorizontalDivider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(0.5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )

                // Middle key — model selector
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp)
                        .then(
                            if (!isLoadingModels) {
                                Modifier.clickable(
                                    interactionSource = middleKeyInteractionSource,
                                    indication = null,
                                ) { modelDropdownExpanded = true }
                            } else {
                                Modifier
                            }
                        )
                        .background(
                            if (isMiddleKeyPressed && !isLoadingModels) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selectedModel?.name ?: "Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false },
                        modifier = Modifier.heightIn(max = 200.dp),
                    ) {
                        availableModels.forEach { model ->
                            val isSelected = selectedModel?.id == model.id &&
                                selectedModel?.name == model.name
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = model.name ?: model.id ?: "Unknown",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                                onClick = {
                                    onSelectModel(model)
                                    modelDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Divider 2
                HorizontalDivider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(0.5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )

                // Right key — context usage (display only)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DataUsage,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = contextUsageK,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Text input row (bottom) — no separator between strip and input ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
}
