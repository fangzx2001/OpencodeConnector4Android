package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.opencode.remote.data.api.dto.ModelInfo

@Composable
fun ModelSelector(
    selectedModel: ModelInfo?,
    availableModels: List<ModelInfo>,
    onSelectModel: (ModelInfo) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.testTag("model_selector")) {
        TextButton(onClick = { if (!isLoading) expanded = true }) {
            Text(
                text = selectedModel?.name ?: "Default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableModels.forEach { model ->
                val isSelected = selectedModel?.id == model.id &&
                    selectedModel?.name == model.name
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = model.name ?: model.id ?: "Unknown",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        onSelectModel(model)
                        expanded = false
                    },
                )
            }
        }
    }
}
