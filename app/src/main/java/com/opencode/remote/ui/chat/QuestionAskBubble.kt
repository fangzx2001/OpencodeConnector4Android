package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.QuestionInfoDto
import com.opencode.remote.ui.strings.AppLocale

@Composable
internal fun QuestionAskBubble(
    questions: List<QuestionInfoDto>,
    onReply: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings

    // For simplicity, handle the first question (most common case)
    val question = questions.firstOrNull() ?: return

    // Track selected options (by label)
    var selectedOptions by remember(questions.firstOrNull()?.question) { mutableStateOf(emptySet<String>()) }
    var customAnswer by remember(questions.firstOrNull()?.question) { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    question.header ?: s.questionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            // Question text
            if (question.question.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            // Option chips
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    question.options.forEach { option ->
                        val isSelected = selectedOptions.contains(option.label)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (question.multiple) {
                                    selectedOptions = if (isSelected) selectedOptions - option.label else selectedOptions + option.label
                                } else {
                                    selectedOptions = setOf(option.label)
                                }
                            },
                            label = {
                                Column {
                                    Text(option.label, fontWeight = FontWeight.Medium)
                                    option.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Custom text input
            if (question.custom) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customAnswer,
                    onValueChange = { customAnswer = it },
                    placeholder = { Text(s.questionCustomPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                )
            }

            // Action buttons
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        val answers = mutableListOf<List<String>>()
                        val selected = selectedOptions.toList()
                        val custom = customAnswer.trim()
                        if (selected.isNotEmpty()) {
                            answers.add(selected)
                        } else if (custom.isNotEmpty()) {
                            answers.add(listOf(custom))
                        }
                        if (answers.isNotEmpty()) {
                            onReply(answers)
                        }
                    },
                    enabled = selectedOptions.isNotEmpty() || customAnswer.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.questionSubmit)
                }

                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.questionDismiss)
                }
            }
        }
    }
}
