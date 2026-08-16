package com.silverbullet.kode.feature.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.feature.connection.presentation.AddEnvironmentUiState

/**
 * The Add Environment form, shared by first-run onboarding and the settings
 * screen so the two entry points cannot drift apart.
 *
 * Field labels, placeholders and button strings mirror T3 Code mobile's
 * `ConnectionsNewRouteScreen`.
 */
@Composable
fun AddEnvironmentForm(
    uiState: AddEnvironmentUiState,
    onHostChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.host,
            onValueChange = onHostChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Host") },
            placeholder = { Text("192.168.1.100:8080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        OutlinedTextField(
            value = uiState.code,
            onValueChange = onCodeChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing code") },
            placeholder = { Text("abc-123-xyz") },
            singleLine = true,
            isError = uiState.error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        )

        uiState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = uiState.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(if (uiState.isSubmitting) "Pairing..." else "Add environment")
        }

        if (uiState.canScanQr) {
            OutlinedButton(
                onClick = onScanQr,
                enabled = !uiState.isScanning && !uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = KodeIcons.QrCode,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                )
                Text("Scan QR code")
            }
        }
    }
}
