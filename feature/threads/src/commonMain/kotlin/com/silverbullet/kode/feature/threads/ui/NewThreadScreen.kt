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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.activeOptionLabels
import com.silverbullet.kode.feature.threads.domain.runtimeModeLabel
import com.silverbullet.kode.feature.threads.presentation.NewThreadUiState
import com.silverbullet.kode.feature.threads.presentation.NewThreadViewModel
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.viewmodel.koinViewModel

private sealed interface OpenPicker {
    data object None : OpenPicker
    data object Project : OpenPicker
    data object Config : OpenPicker
}

@Composable
fun NewThreadRoute(
    onCreated: (ThreadId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewThreadViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The thread exists server-side before the shell subscription reports it,
    // so navigation is driven by the command result, not by the list.
    LaunchedEffect(uiState.createdThreadId) {
        val created = uiState.createdThreadId ?: return@LaunchedEffect
        viewModel.onNavigated()
        onCreated(created)
    }

    NewThreadScreen(
        uiState = uiState,
        onProjectSelected = viewModel::onProjectSelected,
        onTitleChanged = viewModel::onTitleChanged,
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
    onProjectSelected: (ProjectId) -> Unit,
    onTitleChanged: (String) -> Unit,
    onModelSelected: (com.silverbullet.kode.feature.threads.domain.ModelOption) -> Unit,
    onModelOptionSelected: (String, JsonPrimitive) -> Unit,
    onRuntimeModeSelected: (String) -> Unit,
    onInteractionModeSelected: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by remember { mutableStateOf<OpenPicker>(OpenPicker.None) }
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

            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                placeholder = { Text("What is this thread about?") },
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
            )

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
            Text("Create thread")
        }
    }

    when (picker) {
        OpenPicker.None -> Unit

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
