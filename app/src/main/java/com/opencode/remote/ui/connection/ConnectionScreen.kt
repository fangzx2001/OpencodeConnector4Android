package com.opencode.remote.ui.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.ui.strings.AppLocale

enum class ConnectionMode { ADD_SERVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
    mode: ConnectionMode? = null,
    onBack: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val s = AppLocale.strings
    var passwordVisible by remember { mutableStateOf(false) }

    // Load persisted language and dark mode on first composition
    LaunchedEffect(Unit) {
        viewModel.loadLanguage()
        viewModel.loadDarkMode()
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
                title = { Text(if (mode == ConnectionMode.ADD_SERVER) s.addServerTitle else s.appTitle) },
                navigationIcon = {
                    if (mode == ConnectionMode.ADD_SERVER && onBack != null) {
                        IconButton(onClick = { onBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = s.helpBack,
                            )
                        }
                    }
                },
                actions = {
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Scrollable form body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))

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

                Spacer(modifier = Modifier.height(32.dp))

                if (mode == ConnectionMode.ADD_SERVER) {
                    OutlinedTextField(
                        value = uiState.serverName,
                        onValueChange = viewModel::onServerNameChange,
                        label = { Text(s.serverName) },
                        placeholder = { Text(s.serverNamePlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isConnecting,
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

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
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) s.passwordHide else s.passwordShow,
                            )
                        }
                    },
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

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Fixed bottom button
            Button(
                onClick = {
                    if (mode == ConnectionMode.ADD_SERVER) viewModel.saveAndConnect()
                    else viewModel.connect()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 12.dp)
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
                        text = if (mode == ConnectionMode.ADD_SERVER) s.saveAndConnect else s.connectButton,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
