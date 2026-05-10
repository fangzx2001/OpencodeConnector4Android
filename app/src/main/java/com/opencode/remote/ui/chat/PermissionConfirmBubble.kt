package com.opencode.remote.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.remote.ui.strings.AppLocale

@Composable
internal fun PermissionConfirmBubble(
    permission: String,
    patterns: List<String>,
    alwaysPatterns: List<String>,
    onReply: (reply: String, message: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    var showRejectReason by remember { mutableStateOf(false) }
    var rejectReasonText by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with warning icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    s.permissionRequired,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Permission description
            Text(
                text = buildString {
                    append(permission)
                    if (patterns.isNotEmpty()) {
                        append(": ")
                        append(patterns.joinToString(", "))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onReply("once", null) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.permissionAllowOnce)
                }

                if (alwaysPatterns.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onReply("always", null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(s.permissionAllowAlways)
                    }
                }

                OutlinedButton(
                    onClick = { showRejectReason = !showRejectReason },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.permissionReject)
                }
            }

            // Optional reject reason field
            AnimatedVisibility(visible = showRejectReason) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rejectReasonText,
                        onValueChange = { rejectReasonText = it },
                        placeholder = { Text(s.permissionRejectReason) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        shape = RoundedCornerShape(8.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                onReply("reject", rejectReasonText.ifBlank { null })
                                showRejectReason = false
                                rejectReasonText = ""
                            }
                        ) {
                            Text(s.permissionReject)
                        }
                    }
                }
            }
        }
    }
}
