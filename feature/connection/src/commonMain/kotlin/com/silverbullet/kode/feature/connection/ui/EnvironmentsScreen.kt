package com.silverbullet.kode.feature.connection.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.connection.presentation.EnvironmentEditForm
import com.silverbullet.kode.feature.connection.presentation.EnvironmentRowState
import com.silverbullet.kode.feature.connection.presentation.EnvironmentStatusTone
import com.silverbullet.kode.feature.connection.presentation.EnvironmentsUiState
import com.silverbullet.kode.feature.connection.presentation.EnvironmentsViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EnvironmentsRoute(
    modifier: Modifier = Modifier,
    viewModel: EnvironmentsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EnvironmentsScreen(
        uiState = uiState,
        onToggleExpanded = viewModel::toggleExpanded,
        onLabelChanged = viewModel::onLabelChanged,
        onUrlChanged = viewModel::onUrlChanged,
        onSave = viewModel::save,
        onReconnect = viewModel::reconnect,
        onRemove = viewModel::remove,
        modifier = modifier,
    )
}

/**
 * The saved environments, one card, one row each — the port of T3 Code's
 * `SettingsEnvironmentsRouteScreen` with `ConnectionEnvironmentRow` accordions.
 */
@Composable
fun EnvironmentsScreen(
    uiState: EnvironmentsUiState,
    onToggleExpanded: (EnvironmentId) -> Unit,
    onLabelChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onSave: () -> Unit,
    onReconnect: (EnvironmentId) -> Unit,
    onRemove: (EnvironmentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The confirmation is UI state: it exists only while the dialog is up.
    var pendingRemoval by remember { mutableStateOf<EnvironmentRowState?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (uiState.isEmpty) {
            EmptyEnvironments()
        } else {
            Card {
                Column {
                    uiState.environments.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = KodeTheme.colors.divider)
                        EnvironmentRow(
                            row = row,
                            form = uiState.form,
                            onToggleExpanded = { onToggleExpanded(row.environmentId) },
                            onLabelChanged = onLabelChanged,
                            onUrlChanged = onUrlChanged,
                            onSave = onSave,
                            onReconnect = { onReconnect(row.environmentId) },
                            onRemoveRequested = { pendingRemoval = row },
                        )
                    }
                }
            }
        }
    }

    pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove environment?") },
            text = { Text("Disconnect and forget ${row.label} on this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onRemove(row.environmentId)
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyEnvironments() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = KodeIcons.Monitor,
            contentDescription = null,
            tint = KodeTheme.colors.muted,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = "No environments connected yet.\nTap + to add one.",
            style = MaterialTheme.typography.bodyMedium,
            color = KodeTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EnvironmentRow(
    row: EnvironmentRowState,
    form: EnvironmentEditForm,
    onToggleExpanded: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onSave: () -> Unit,
    onReconnect: () -> Unit,
    onRemoveRequested: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Only the header toggles; a tap inside the expanded editor must not
        // collapse the row out from under the keyboard.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(row.status.tone)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.displayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = KodeTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = KodeIcons.ChevronDown,
                contentDescription = if (row.expanded) "Collapse" else "Expand",
                tint = KodeTheme.colors.muted,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = if (row.expanded) 180f else 0f },
            )
        }

        Text(
            text = row.status.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (row.status.tone) {
                EnvironmentStatusTone.BAD -> MaterialTheme.colorScheme.error
                else -> KodeTheme.colors.muted
            },
        )

        if (row.expanded) {
            OutlinedTextField(
                value = form.label,
                onValueChange = onLabelChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Label") },
                placeholder = { Text("My MacBook") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.url,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL") },
                placeholder = { Text("192.168.1.100:8080") },
                singleLine = true,
                isError = form.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            form.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSave,
                    enabled = !form.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (form.isSaving) "Saving..." else "Save")
                }
                IconButton(onClick = onReconnect) {
                    Icon(
                        imageVector = KodeIcons.Refresh,
                        contentDescription = "Reconnect",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemoveRequested) {
                    Icon(
                        imageVector = KodeIcons.Trash,
                        contentDescription = "Remove environment",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * T3 Code's status dot palette: emerald for connected, amber while a connection
 * is being attempted, red for offline/failed.
 */
@Composable
private fun StatusDot(tone: EnvironmentStatusTone) {
    val color = when (tone) {
        EnvironmentStatusTone.GOOD -> Color(0xFF34D399)
        EnvironmentStatusTone.PENDING -> Color(0xFFF59E0B)
        EnvironmentStatusTone.BAD -> Color(0xFFEF4444)
    }
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}
