package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.DraftAttachment
import com.silverbullet.kode.feature.threads.domain.activeOptionLabels
import com.silverbullet.kode.feature.threads.domain.runtimeModeLabel
import com.silverbullet.kode.feature.threads.presentation.NewThreadUiState
import com.silverbullet.kode.feature.threads.presentation.NewThreadViewModel
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.viewmodel.koinViewModel

private sealed interface OpenPicker {
    data object None : OpenPicker
    data object Environment : OpenPicker
    data object Project : OpenPicker
    data object Config : OpenPicker
}

@Composable
fun NewThreadRoute(
    onCreated: (EnvironmentId, ThreadId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewThreadViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The thread exists server-side before the shell subscription reports it,
    // so navigation is driven by the command result, not by the list.
    LaunchedEffect(uiState.created) {
        val created = uiState.created ?: return@LaunchedEffect
        viewModel.onNavigated()
        onCreated(created.environmentId, created.threadId)
    }

    NewThreadScreen(
        uiState = uiState,
        onEnvironmentSelected = viewModel::onEnvironmentSelected,
        onProjectSelected = viewModel::onProjectSelected,
        onMessageChanged = viewModel::onMessageChanged,
        onPickImages = viewModel::onPickImages,
        onRemoveAttachment = viewModel::onRemoveAttachment,
        onModelSelected = { option -> viewModel.onModelSelected(option) },
        onModelOptionSelected = viewModel::onModelOptionSelected,
        onRuntimeModeSelected = viewModel::onRuntimeModeSelected,
        onInteractionModeSelected = viewModel::onInteractionModeSelected,
        onCreate = viewModel::create,
        modifier = modifier,
    )
}

@Composable
fun NewThreadScreen(
    uiState: NewThreadUiState,
    onEnvironmentSelected: (EnvironmentId) -> Unit,
    onProjectSelected: (ProjectId) -> Unit,
    onMessageChanged: (String) -> Unit,
    onPickImages: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelSelected: (com.silverbullet.kode.feature.threads.domain.ModelOption) -> Unit,
    onModelOptionSelected: (String, JsonPrimitive) -> Unit,
    onRuntimeModeSelected: (String) -> Unit,
    onInteractionModeSelected: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by remember { mutableStateOf<OpenPicker>(OpenPicker.None) }
    var previewAttachment by remember { mutableStateOf<DraftAttachment?>(null) }
    val colors = KodeTheme.colors
    val selectedProject = uiState.projects.firstOrNull { it.id == uiState.projectId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!uiState.hasProjects) {
                Text(
                    text = "No projects on this environment yet. Add one from the desktop app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }

            // No title field: as in T3 Code's mobile client, the title is
            // derived from this first message and refined server-side.
            OutlinedTextField(
                value = uiState.message,
                onValueChange = onMessageChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("First message") },
                placeholder = { Text("What should the agent do?") },
                textStyle = MaterialTheme.typography.bodyLarge,
                minLines = 3,
            )

            // Attachments sit directly under the message they belong to, above
            // the environment/project/agent settings, so the first message reads
            // as one composed unit rather than as a form field plus an extra.
            if (uiState.canAttach) {
                ComposerAttachmentStrip(
                    attachments = uiState.attachments,
                    onRemove = onRemoveAttachment,
                    onPreview = { previewAttachment = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onPickImages, enabled = uiState.canAttachMore) {
                    Icon(
                        imageVector = KodeIcons.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Attach images",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // "on <environment>": static until there is more than one, as in
            // T3's composer control.
            if (uiState.environments.isNotEmpty()) {
                SettingRow(
                    label = "Environment",
                    value = uiState.selectedEnvironmentLabel,
                    onClick = { picker = OpenPicker.Environment },
                    enabled = uiState.canPickEnvironment,
                )
            }

            SettingRow(
                label = "Project",
                value = selectedProject?.title ?: "Choose a project",
                onClick = { picker = OpenPicker.Project },
                enabled = uiState.hasProjects,
            )

            // One sheet for the agent configuration, the same one the composer
            // opens, so the two screens cannot drift apart.
            SettingRow(
                label = "Agent",
                value = agentSummary(uiState),
                onClick = { picker = OpenPicker.Config },
                enabled = !uiState.catalog.isEmpty,
            )

            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Button(
            onClick = onCreate,
            enabled = uiState.canCreate,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text("Start thread")
        }
    }

    previewAttachment?.let { attachment ->
        ImagePreviewDialog(
            model = attachment.previewUri,
            onDismiss = { previewAttachment = null },
        )
    }

    when (picker) {
        OpenPicker.None -> Unit

        OpenPicker.Environment -> PickerSheet(
            title = "Environment",
            entries = uiState.environments.map {
                PickerEntry(
                    id = it.environmentId.value,
                    label = it.label,
                    description = if (it.isConnected) "Connected" else "Not connected",
                )
            },
            selectedId = uiState.environmentId?.value,
            onSelect = {
                onEnvironmentSelected(EnvironmentId(it))
                picker = OpenPicker.None
            },
            onDismiss = { picker = OpenPicker.None },
        )

        OpenPicker.Project -> PickerSheet(
            title = "Project",
            entries = uiState.projects.map {
                PickerEntry(id = it.id.value, label = it.title, description = it.workspaceRoot)
            },
            selectedId = uiState.projectId?.value,
            onSelect = {
                onProjectSelected(ProjectId(it))
                picker = OpenPicker.None
            },
            onDismiss = { picker = OpenPicker.None },
        )

        OpenPicker.Config -> ThreadConfigSheet(
            config = ThreadConfig(
                catalog = uiState.catalog,
                selectedModel = uiState.selectedModel,
                // A thread that does not exist yet cannot be locked to a driver.
                lockedDriver = null,
                optionDescriptors = uiState.optionDescriptors,
                runtimeMode = uiState.runtimeMode,
                interactionMode = uiState.interactionMode,
            ),
            onModelSelected = onModelSelected,
            onOptionSelected = onModelOptionSelected,
            onRuntimeModeSelected = onRuntimeModeSelected,
            onInteractionModeSelected = onInteractionModeSelected,
            onDismiss = { picker = OpenPicker.None },
        )
    }
}

/** "Claude Opus 5 · Medium · Supervised" — the same summary the composer shows. */
private fun agentSummary(uiState: NewThreadUiState): String = buildList {
    uiState.selectedModel?.label?.let(::add)
    addAll(uiState.optionDescriptors.activeOptionLabels())
    add(runtimeModeLabel(uiState.runtimeMode))
}.joinToString(" · ").ifEmpty { "No models available" }

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = KodeTheme.colors.muted,
            )
            ConfigPill(label = "", value = value, onClick = onClick, enabled = enabled)
        }
    }
}
