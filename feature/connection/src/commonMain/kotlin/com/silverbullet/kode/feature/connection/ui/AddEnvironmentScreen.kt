package com.silverbullet.kode.feature.connection.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.feature.connection.presentation.AddEnvironmentViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The settings-hosted Add Environment screen. Unlike onboarding, this host has
 * somewhere to go back to: a successful pairing pops the screen.
 */
@Composable
fun AddEnvironmentRoute(
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEnvironmentViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.added) {
        if (uiState.added) {
            viewModel.onAddedConsumed()
            onAdded()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Run `npx t3 pair` on your desktop, then scan the QR code it shows " +
                "or enter the host and pairing code below.",
            style = MaterialTheme.typography.bodyMedium,
        )

        AddEnvironmentForm(
            uiState = uiState,
            onHostChanged = viewModel::onHostChanged,
            onCodeChanged = viewModel::onCodeChanged,
            onSubmit = viewModel::submit,
            onScanQr = viewModel::scanQr,
        )
    }
}
