package com.silverbullet.kode.feature.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.feature.connection.presentation.ConnectionUiState
import com.silverbullet.kode.feature.connection.presentation.ConnectionViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectionRoute(
    modifier: Modifier = Modifier,
    viewModel: ConnectionViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionScreen(
        uiState = uiState,
        onPairingUrlChanged = viewModel::onPairingUrlChanged,
        onPair = viewModel::pair,
        onRetry = viewModel::retry,
        onUnpair = viewModel::unpair,
        modifier = modifier,
    )
}

/**
 * Stateless so it can be previewed and, later, reused from a SwiftUI host via
 * the same view model.
 */
@Composable
fun ConnectionScreen(
    uiState: ConnectionUiState,
    onPairingUrlChanged: (String) -> Unit,
    onPair: () -> Unit,
    onRetry: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Kode", style = MaterialTheme.typography.headlineMedium)

        when (val connection = uiState.connection) {
            ConnectionState.Unpaired -> PairingForm(
                uiState = uiState,
                onPairingUrlChanged = onPairingUrlChanged,
                onPair = onPair,
            )

            else -> ConnectionStatusCard(
                connection = connection,
                onRetry = onRetry,
                onUnpair = onUnpair,
            )
        }
    }
}

@Composable
private fun PairingForm(
    uiState: ConnectionUiState,
    onPairingUrlChanged: (String) -> Unit,
    onPair: () -> Unit,
) {
    Text(
        "Run `npx t3 pair` on your desktop and paste the pairing URL it prints.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = uiState.form.pairingUrl,
        onValueChange = onPairingUrlChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Pairing URL") },
        placeholder = { Text("http://100.x.y.z:3773/#token=…") },
        singleLine = true,
        isError = uiState.form.error != null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
    )

    uiState.form.error?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Button(
        onClick = onPair,
        enabled = uiState.form.pairingUrl.isNotBlank() && !uiState.form.isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.form.isSubmitting) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
        }
        Text("Pair")
    }
}

@Composable
private fun ConnectionStatusCard(
    connection: ConnectionState,
    onRetry: () -> Unit,
    onUnpair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (connection) {
                is ConnectionState.Connected -> {
                    Text("Connected", style = MaterialTheme.typography.titleMedium)
                    LabelledValue("Environment", connection.environment.label)
                    LabelledValue(
                        "Server",
                        "v${connection.environment.serverVersion} · " +
                            "${connection.environment.platform.os}/" +
                            connection.environment.platform.arch,
                    )
                    LabelledValue("Working directory", connection.workingDirectory)
                }

                ConnectionState.Connecting -> {
                    Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator()
                }

                is ConnectionState.Reconnecting -> {
                    Text("Reconnecting…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Attempt ${connection.attempt} · retrying in " +
                            "${connection.retryInMillis / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(connection.detail, style = MaterialTheme.typography.bodySmall)
                }

                is ConnectionState.Blocked -> {
                    Text("Cannot connect", style = MaterialTheme.typography.titleMedium)
                    Text(
                        connection.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                ConnectionState.Unpaired -> Unit
            }

            Column {
                TextButton(onClick = onRetry) { Text("Retry now") }
                TextButton(onClick = onUnpair) { Text("Unpair") }
            }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
