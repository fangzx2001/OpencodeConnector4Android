package com.opencode.remote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.FileNode
import com.opencode.remote.data.api.dto.ModelInfo

@Composable
fun RightPanel(
    files: List<FileNode>,
    currentPath: String,
    onNavigateToDirectory: (String) -> Unit,
    isLoadingFiles: Boolean,
    selectedModel: ModelInfo?,
    availableModels: List<ModelInfo>,
    onSelectModel: (ModelInfo) -> Unit,
    isLoadingModels: Boolean,
    contextUsageK: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column {
            // Path breadcrumb
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // FileTreeView — fills remaining space
            FileTreeView(
                files = files,
                currentPath = currentPath,
                onNavigateToDirectory = onNavigateToDirectory,
                isLoading = isLoadingFiles,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            HorizontalDivider()

            // Bottom bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelSelector(
                    selectedModel = selectedModel,
                    availableModels = availableModels,
                    onSelectModel = onSelectModel,
                    isLoading = isLoadingModels,
                )
                ContextUsageDisplay(usageK = contextUsageK)
            }
        }
    }
}
