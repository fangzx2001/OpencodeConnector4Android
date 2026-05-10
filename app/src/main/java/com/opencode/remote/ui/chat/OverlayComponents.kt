package com.opencode.remote.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

// ─── Chat Selection Config ────────────────────────────────────────────────

@Composable
internal fun ChatSelectionConfigButton(
    hasExplicitOverrides: Boolean,
    onClick: () -> Unit,
) {
    val s = AppLocale.strings
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = s.selectAgent,
            tint = if (hasExplicitOverrides) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
internal fun ChatSelectionConfigDialog(
    selection: ChatSelectionUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onAgentSelected: (String?) -> Unit,
    onModelSelected: (ModelSelectionRef?) -> Unit,
    onVariantSelected: (String?) -> Unit,
) {
    val s = AppLocale.strings
    var agentExpanded by remember(selection.draft.agent, selection.isDialogOpen) { mutableStateOf(false) }
    var modelExpanded by remember(selection.draft.model, selection.isDialogOpen) { mutableStateOf(false) }
    var variantExpanded by remember(selection.draft.variant, selection.isDialogOpen) { mutableStateOf(false) }

    fun collapseExpandedMenus() {
        agentExpanded = false
        modelExpanded = false
        variantExpanded = false
    }

    val draftModelLabel = selection.resolveModel(selection.draft.model)?.displayLabel ?: s.selectionAuto
    val draftVariantLabel = selection.draft.variant ?: s.selectionAuto

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .width(432.dp)
                .pointerInput(agentExpanded, modelExpanded, variantExpanded) {
                    detectTapGestures {
                        if (agentExpanded || modelExpanded || variantExpanded) {
                            collapseExpandedMenus()
                        }
                    }
                },
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.selectionDialogTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionDropdownRow(
                        label = s.selectionPersona,
                        value = selection.draft.agent ?: s.selectionAuto,
                        expanded = agentExpanded,
                        onExpandedChange = {
                            collapseExpandedMenus()
                            agentExpanded = it
                            if (it) {
                                modelExpanded = false
                                variantExpanded = false
                            }
                        },
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.selectionAuto, fontWeight = if (selection.draft.agent == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                agentExpanded = false
                                onAgentSelected(null)
                            },
                        )
                        HorizontalDivider()
                        selection.availableAgents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            agent.name,
                                            fontWeight = if (selection.draft.agent == agent.name) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        agent.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    agentExpanded = false
                                    onAgentSelected(agent.name)
                                },
                            )
                        }
                    }

                    SelectionDropdownRow(
                        label = s.selectionModel,
                        value = draftModelLabel,
                        expanded = modelExpanded,
                        onExpandedChange = {
                            collapseExpandedMenus()
                            modelExpanded = it
                            if (it) {
                                agentExpanded = false
                                variantExpanded = false
                            }
                        },
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.selectionAuto, fontWeight = if (selection.draft.model == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                modelExpanded = false
                                onModelSelected(null)
                            },
                        )
                        HorizontalDivider()
                        selection.availableModels.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.displayLabel,
                                        fontWeight = if (selection.draft.model == option.ref) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    modelExpanded = false
                                    onModelSelected(option.ref)
                                },
                            )
                        }
                    }

                    SelectionDropdownRow(
                        label = s.selectionLevel,
                        value = draftVariantLabel,
                        expanded = variantExpanded,
                        onExpandedChange = {
                            collapseExpandedMenus()
                            variantExpanded = it
                            if (it) {
                                agentExpanded = false
                                modelExpanded = false
                            }
                        },
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.selectionAuto, fontWeight = if (selection.draft.variant == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                variantExpanded = false
                                onVariantSelected(null)
                            },
                        )
                        if (selection.draftVariants.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        selection.draftVariants.forEach { variant ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        variant,
                                        fontWeight = if (selection.draft.variant == variant) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    variantExpanded = false
                                    onVariantSelected(variant)
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(s.cancel)
                    }
                    TextButton(onClick = onConfirm) {
                        Text(s.confirm)
                    }
                }
            }
        }
}
}

@Composable
private fun SelectionDropdownRow(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                shape = MaterialTheme.shapes.large,
                tonalElevation = if (expanded) 2.dp else 0.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                color = if (expanded) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 220.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        menuContent()
                    }
                }
            }
        }
    }
}
