package com.opencode.remote.ui.connection

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.data.download.DownloadHelper
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.strings.enStrings
import com.opencode.remote.ui.strings.zhStrings
import com.opencode.remote.ui.update.UpdateDialog
import com.opencode.remote.ui.update.UpdateUiState
import com.opencode.remote.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onHelpClick: () -> Unit = {},
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val s = AppLocale.strings
    var showLangPicker by remember { mutableStateOf(false) }

    val updateViewModel: UpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.uiState.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Load persisted language and dark mode on first composition
    LaunchedEffect(Unit) {
        viewModel.loadLanguage()
        viewModel.loadDarkMode()
        updateViewModel.checkForUpdate()
    }

    // Navigate when connected
    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) {
            onConnected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.appTitle) },
                actions = {
                    // Update button - appears when new version available
                    if (updateState is UpdateUiState.Available) {
                        IconButton(onClick = { showUpdateDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Update available",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Language toggle
                    Box {
                        TextButton(onClick = { showLangPicker = true }) {
                            Text(
                                text = "Aa",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        DropdownMenu(
                            expanded = showLangPicker,
                            onDismissRequest = { showLangPicker = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = AppLocale.language == "en",
                                            onClick = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("English")
                                    }
                                },
                                onClick = {
                                    AppLocale.language = "en"
                                    viewModel.saveLanguage("en")
                                    showLangPicker = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = AppLocale.language == "zh",
                                            onClick = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("中文")
                                    }
                                },
                                onClick = {
                                    AppLocale.language = "zh"
                                    viewModel.saveLanguage("zh")
                                    showLangPicker = false
                                },
                            )
                        }
                    }
                    // Help button
                    IconButton(onClick = onHelpClick) {
                        Icon(Icons.Default.HelpOutline, contentDescription = s.helpLabel)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = s.appTitle,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = s.connectSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = uiState.host,
                onValueChange = viewModel::onHostChange,
                label = { Text(s.hostLabel) },
                placeholder = { Text(s.hostPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnecting,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChange,
                label = { Text(s.portLabel) },
                placeholder = { Text(s.portPlaceholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnecting,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(s.usernameLabel) },
                placeholder = { Text(s.usernamePlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnecting,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(s.passwordLabel) },
                placeholder = { Text(s.passwordPlaceholder) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isConnecting,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(s.useTlsLabel, style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = uiState.useTls,
                    onCheckedChange = viewModel::onUseTlsChange,
                    enabled = !uiState.isConnecting,
                )
            }

            if (uiState.useTls) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        s.insecureTrustLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = uiState.insecureTrust,
                        onCheckedChange = viewModel::onInsecureTrustChange,
                        enabled = !uiState.isConnecting,
                    )
                }
            }

            uiState.error?.let { errorMsg ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = viewModel::connect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !uiState.isConnecting,
            ) {
                if (uiState.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(s.connecting)
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = s.connectButton,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.tipsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = s.tipsContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    )
                }
            }
        }
    }

    // Update dialog
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
                    DownloadHelper.downloadApk(
                        context,
                        avail.downloadUrl,
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
