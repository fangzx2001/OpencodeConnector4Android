package com.opencode.remote.ui.help

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.download.DownloadHelper
import com.opencode.remote.data.github.GitHubReleaseService
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.update.UpdateDialog
import com.opencode.remote.ui.update.UpdateUiState
import com.opencode.remote.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    updateViewModel: UpdateViewModel,
) {
    val scrollState = rememberScrollState()
    val s = AppLocale.strings
    val context = LocalContext.current
    val updateState by updateViewModel.uiState.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.helpTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.helpBack)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionTitle(s.helpWhatIs)
            SectionBody(s.helpWhatIsBody)

            SectionTitle(s.servers)
            SectionBody(s.helpServerMgmtBody)
            SectionBullet(s.helpServerMgmtBullet1)
            SectionBullet(s.helpServerMgmtBullet2)
            SectionBullet(s.helpServerMgmtBullet3)
            SectionBullet(s.helpServerMgmtBullet4)
            SectionBullet(s.helpServerMgmtBullet5)

            SectionTitle(s.helpStep1)
            SectionBody(s.helpStep1Body)
            CodeBlock(s.helpStep1Code)
            SectionBody(s.helpStep1After)

            SectionTitle(s.helpStep2)
            SectionBullet(s.helpStep2Bullet1)
            SectionBullet(s.helpStep2Bullet2)
            SectionBullet(s.helpStep2Bullet3)
            SectionBullet(s.helpStep2Bullet4)
            SectionBody(s.helpStep2HowToIp)
            SectionBullet(s.helpStep2Win)
            SectionBullet(s.helpStep2Mac)
            SectionBody(s.helpStep2After)

            SectionTitle(s.helpStep3)
            SectionBody(s.helpStep3Body)
            SectionBullet(s.helpStep3Bullet1)
            SectionBullet(s.helpStep3Bullet2)
            SectionBullet(s.helpStep3Bullet3)
            SectionBullet(s.helpStep3Bullet4)

            SectionTitle(s.helpStep4)
            SectionBullet(s.helpStep4Bullet1)
            SectionBullet(s.helpStep4Bullet2)
            SectionBullet(s.helpStep4Bullet3)
            SectionBullet(s.helpStep4Bullet4)
            SectionBullet(s.helpStep4Bullet5)

            SectionTitle(s.helpAgentTitle)
            SectionBody(s.helpAgentBody)
            SectionBullet(s.helpAgentBullet1)
            SectionBullet(s.helpAgentBullet2)
            SectionBullet(s.helpAgentBullet3)

            SectionTitle(s.helpTodoTitle)
            SectionBody(s.helpTodoBody)

            SectionTitle(s.helpFaqTitle)
            HelpQA(s.helpFaq1Q, s.helpFaq1A)
            HelpQA(s.helpFaq2Q, s.helpFaq2A)
            HelpQA(s.helpFaq3Q, s.helpFaq3A)
            HelpQA(s.helpFaq4Q, s.helpFaq4A)
            HelpQA(s.helpFaq5Q, s.helpFaq5A)
            HelpQA(s.helpFaq6Q, s.helpFaq6A)

            SectionTitle(s.helpSecurityTitle)
            SectionBullet(s.helpSecBullet1)
            SectionBullet(s.helpSecBullet2)
            SectionBullet(s.helpSecBullet3)
            SectionBullet(s.helpSecBullet4)

            SectionTitle(s.helpUpdateTitle)
            SectionBody(s.helpUpdateBody)
            SectionBullet(s.helpUpdateBullet1)
            SectionBullet(s.helpUpdateBullet2)
            SectionBullet(s.helpUpdateBullet3)
            SectionBullet(s.helpUpdateBullet4)

            Spacer(modifier = Modifier.height(8.dp))

            // Manual "Check for Updates" button
            OutlinedButton(
                onClick = { updateViewModel.checkForUpdate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = updateState !is UpdateUiState.Checking,
            ) {
                if (updateState is UpdateUiState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.helpCheckUpdateChecking)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.helpCheckUpdateButton)
                }
            }

            // Show result feedback inline below button
            when (updateState) {
                is UpdateUiState.UpToDate -> {
                    Text(
                        text = "✓ ${s.updateUpToDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is UpdateUiState.Error -> {
                    Text(
                        text = (updateState as UpdateUiState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is UpdateUiState.Available -> {
                    TextButton(
                        onClick = { showUpdateDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${s.updateAvailable}: v${(updateState as UpdateUiState.Available).version}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                else -> { /* Idle or Checking — no inline feedback */ }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = s.helpVersion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Update dialog (shared with ConnectionScreen flow)
    if (showUpdateDialog && updateState is UpdateUiState.Available) {
        val avail = updateState as UpdateUiState.Available
        UpdateDialog(
            version = avail.version,
            changelog = avail.changelog,
            changelogTitle = s.updateChangelog,
            downloadText = s.updateDownload,
            closeText = s.updateClose,
            noChangelogText = "No changelog provided.",
            onDownload = {
                showUpdateDialog = false
                if (avail.downloadUrl != null) {
                    val proxiedUrl = GitHubReleaseService.proxiedDownloadUrl(avail.downloadUrl)
                    DownloadHelper.downloadApk(
                        context,
                        proxiedUrl,
                        "OConnector-v${avail.version}.apk"
                    )
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(avail.releaseUrl))
                    context.startActivity(intent)
                }
            },
            onDismiss = { showUpdateDialog = false }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SectionBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SectionBullet(text: String) {
    Row(modifier = Modifier.padding(start = 8.dp)) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun HelpQA(question: String, answer: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Default.QuestionAnswer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
