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
    if (questions.isEmpty()) return

    var currentIndex by remember(questions) { mutableStateOf(0) }

    // Per-question selected options
    val selectedOptionsList = remember(questions) {
        questions.map { mutableStateOf(emptySet<String>()) }
    }
    // Per-question custom answers
    val customAnswerList = remember(questions) {
        questions.map { mutableStateOf("") }
    }

    val totalQuestions = questions.size
    val question = questions[currentIndex]
    val isFirst = currentIndex == 0
    val isLast = currentIndex == totalQuestions - 1

    val selectedOptions by selectedOptionsList[currentIndex]
    val customAnswer by customAnswerList[currentIndex]

    val hasAnswer = if (question.options.isNotEmpty()) {
        selectedOptions.isNotEmpty()
    } else {
        customAnswer.isNotBlank()
    }

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

            // Progress (only for multi-question)
            if (totalQuestions > 1) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${s.questionStep} ${currentIndex + 1} / $totalQuestions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (currentIndex + 1).toFloat() / totalQuestions },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer,
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
                                val newSet = if (question.multiple) {
                                    if (isSelected) selectedOptions - option.label
                                    else selectedOptions + option.label
                                } else {
                                    setOf(option.label)
                                }
                                selectedOptionsList[currentIndex].value = newSet
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
                    onValueChange = { customAnswerList[currentIndex].value = it },
                    placeholder = { Text(s.questionCustomPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                )
            }

            // Navigation buttons
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isFirst) {
                    OutlinedButton(
                        onClick = { currentIndex-- },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s.questionBack)
                    }
                }

                if (!isLast) {
                    Button(
                        onClick = { currentIndex++ },
                        enabled = hasAnswer,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s.questionNext)
                    }
                }
            }

            // Submit + Dismiss
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val answers = questions.indices.mapNotNull { idx ->
                            val sel = selectedOptionsList[idx].value
                            val custom = customAnswerList[idx].value
                            when {
                                sel.isNotEmpty() -> sel.toList()
                                custom.isNotBlank() -> listOf(custom)
                                else -> null
                            }
                        }
                        if (answers.isNotEmpty()) {
                            onReply(answers)
                        }
                    },
                    enabled = isLast && hasAnswer,
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
