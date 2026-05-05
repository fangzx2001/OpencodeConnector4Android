package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.AgentInfo
import com.opencode.remote.data.api.dto.TodoItem
import com.opencode.remote.ui.strings.AppLocale

// ─── Todo Panel ───────────────────────────────────────────────────────────

@Composable
internal fun TodoPanel(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    Card(
        modifier = modifier
            .width(280.dp)
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = s.todoTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = s.close)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            todos.forEach { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = when (todo.status) {
                            "completed" -> Icons.Default.CheckCircle
                            "in_progress" -> Icons.Default.PlayCircle
                            else -> Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (todo.status) {
                            "completed" -> MaterialTheme.colorScheme.tertiary
                            "in_progress" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        text = todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (todos.isEmpty()) {
                Text(
                    text = s.noTodos,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Agent Picker Button ──────────────────────────────────────────────────

@Composable
internal fun AgentPickerButton(
    selectedAgent: String?,
    availableAgents: List<AgentInfo>,
    showAgentPicker: Boolean,
    onTogglePicker: () -> Unit,
    onSelectAgent: (String?) -> Unit,
) {
    val s = AppLocale.strings
    Box {
        IconButton(onClick = onTogglePicker) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = s.selectAgent,
                tint = if (selectedAgent != null)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = showAgentPicker,
            onDismissRequest = { if (showAgentPicker) onTogglePicker() },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "${s.defaultAgent} (${availableAgents.firstOrNull { it.mode == "primary" }?.name ?: "auto"})",
                        fontWeight = if (selectedAgent == null) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                leadingIcon = {
                    RadioButton(
                        selected = selectedAgent == null,
                        onClick = { onSelectAgent(null) },
                    )
                },
                onClick = { onSelectAgent(null) },
            )
            HorizontalDivider()
            availableAgents.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                agent.name,
                                fontWeight = if (selectedAgent == agent.name) FontWeight.Bold else FontWeight.Normal,
                            )
                            agent.description?.let { desc ->
                                val shortDesc = if (desc.length > 60) desc.take(57) + "..." else desc
                                Text(
                                    shortDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        RadioButton(
                            selected = selectedAgent == agent.name,
                            onClick = { onSelectAgent(agent.name) },
                        )
                    },
                    onClick = { onSelectAgent(agent.name) },
                )
            }
        }
    }
}
