package com.silverbullet.kode.feature.voice.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.voice.presentation.EnvironmentVoiceRow
import com.silverbullet.kode.feature.voice.presentation.VoiceSettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings → Voice: the feature toggle plus a voice server binding per environment,
 * following the environments screen's accordion idiom.
 */
@Composable
fun VoiceSettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: VoiceSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<EnvironmentId?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SectionCard(title = "Dictation") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Refine transcript", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "After you stop talking, a fast model fixes misheard technical terms. " +
                            "It never rewrites your prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KodeTheme.colors.muted,
                    )
                }
                Switch(
                    checked = state.refinementEnabled,
                    onCheckedChange = { viewModel.toggleRefinement() },
                )
            }
        }

        SectionCard(title = "Voice servers") {
            if (state.rows.isEmpty()) {
                Text(
                    text = "Pair an environment first — voice servers bind to environments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KodeTheme.colors.muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
            state.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = KodeTheme.colors.divider)
                EnvironmentBindingRow(
                    row = row,
                    expanded = expanded == row.environmentId,
                    onToggle = {
                        expanded = if (expanded == row.environmentId) null else row.environmentId
                    },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = KodeTheme.colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Card(shape = RoundedCornerShape(24.dp)) {
            Column { content() }
        }
    }
}

@Composable
private fun EnvironmentBindingRow(
    row: EnvironmentVoiceRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: VoiceSettingsViewModel,
) {
    var confirmUnbind by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = KodeIcons.Mic,
                contentDescription = null,
                tint = if (row.binding != null) {
                    KodeTheme.colors.success
                } else {
                    KodeTheme.colors.muted
                },
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(row.environmentLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = row.binding?.let { "Bound to ${it.label} · ${it.serverUrl}" } ?: "No voice server",
                    style = MaterialTheme.typography.bodySmall,
                    color = KodeTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (row.binding != null) {
                    OutlinedButton(onClick = { confirmUnbind = true }) {
                        Text("Unbind voice server")
                    }
                } else {
                    BindControls(row = row, viewModel = viewModel)
                }
            }
        }
    }

    if (confirmUnbind) {
        AlertDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text("Unbind voice server?") },
            text = { Text("Voice prompts for ${row.environmentLabel} will stop working until you pair again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnbind = false
                        viewModel.unbind(row.environmentId)
                    },
                ) { Text("Unbind", color = KodeTheme.colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnbind = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BindControls(
    row: EnvironmentVoiceRow,
    viewModel: VoiceSettingsViewModel,
) {
    val form = row.form

    Text(
        text = "Run `voiceserver pair` on ${row.environmentHost ?: "the machine"} and scan its QR, " +
            "or paste the pairing link.",
        style = MaterialTheme.typography.bodySmall,
        color = KodeTheme.colors.muted,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (viewModel.canScanQr) {
            Button(onClick = { viewModel.scanQr(row.environmentId) }, enabled = !form.pairing) {
                Icon(KodeIcons.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Scan QR")
            }
        }
        OutlinedButton(onClick = { viewModel.probe(row.environmentId) }, enabled = !form.probing) {
            if (form.probing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text("Find on ${row.environmentHost ?: "host"}")
            }
        }
    }

    form.discoveredLabel?.let { label ->
        Text(
            text = "Found \"$label\" — enter its pairing code below.",
            style = MaterialTheme.typography.bodySmall,
            color = KodeTheme.colors.success,
        )
    }

    OutlinedTextField(
        value = form.serverUrl,
        onValueChange = { viewModel.onServerUrlChanged(row.environmentId, it) },
        label = { Text("Server address or pairing link") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.code,
        onValueChange = { viewModel.onCodeChanged(row.environmentId, it) },
        label = { Text("Pairing code") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    form.error?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Button(
        onClick = { viewModel.pair(row.environmentId) },
        enabled = !form.pairing,
    ) {
        if (form.pairing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(6.dp))
        }
        Text("Pair")
    }
    Spacer(Modifier.height(2.dp))
}
