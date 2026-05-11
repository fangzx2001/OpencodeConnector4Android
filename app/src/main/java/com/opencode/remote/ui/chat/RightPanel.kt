package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.FileNode

@Composable
fun RightPanel(
    files: List<FileNode>,
    currentPath: String,
    onNavigateToDirectory: (String) -> Unit,
    isLoadingFiles: Boolean,
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    expandedFilePath: String? = null,
    expandedFileContent: String? = null,
    isLoadingFileContent: Boolean = false,
    onToggleFilePreview: ((FileNode) -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
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
                onNavigateUp = onNavigateUp,
                expandedFilePath = expandedFilePath,
                expandedFileContent = expandedFileContent,
                isLoadingFileContent = isLoadingFileContent,
                onToggleFilePreview = onToggleFilePreview,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
