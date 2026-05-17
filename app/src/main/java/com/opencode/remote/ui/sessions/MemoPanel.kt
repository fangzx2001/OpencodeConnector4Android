package com.opencode.remote.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.MemoEntry
import com.opencode.remote.ui.strings.AppLocale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoPanel(
    projectName: String,
    memos: List<MemoEntry>,
    onAdd: () -> Unit,
    onUpdate: (MemoEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column {
            // Header: project name + close button
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${s.memoPanel} · $projectName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = s.close,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Add button
            TextButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.newMemo)
            }

            HorizontalDivider()

            if (memos.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.noMemos,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.noMemosHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                // Memo list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(memos, key = { it.id }) { memo ->
                        MemoItem(
                            memo = memo,
                            onUpdate = onUpdate,
                            onDelete = { onDelete(it) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoItem(
    memo: MemoEntry,
    onUpdate: (MemoEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val s = AppLocale.strings
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editTitle by remember(memo.id, memo.title) { mutableStateOf(memo.title) }
    var editContent by remember(memo.id, memo.content) { mutableStateOf(memo.content) }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(s.deleteMemo) },
            text = { Text(s.deleteMemoConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(memo.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(s.close) }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { showDeleteDialog = true },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Collapsed row: checkbox + title + expand icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = memo.isDone,
                    onCheckedChange = { onUpdate(memo.copy(isDone = it)) },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = memo.title.ifBlank { s.memoTitleHint },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (memo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (memo.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Expanded: editable title + content
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text(s.memoTitleHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text(s.memoContentHint) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            onUpdate(memo.copy(title = editTitle, content = editContent))
                            expanded = false
                        }) {
                            Text(s.close)
                        }
                    }
                }
            }
        }
    }
}
