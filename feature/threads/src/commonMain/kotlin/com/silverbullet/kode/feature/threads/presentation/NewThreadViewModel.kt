package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.model.InteractionMode
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.core.model.RuntimeMode
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.ModelOption
import com.silverbullet.kode.feature.threads.domain.ProviderCatalog
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.domain.applyOptionSelection
import com.silverbullet.kode.feature.threads.domain.resolveOptionDescriptors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Composes a new thread: which project it belongs to, how the agent is
 * configured, and the first message that starts it.
 *
 * There is no title field — as in T3 Code's mobile client, the title is
 * derived from the first message and refined server-side from `titleSeed`.
 *
 * Model choice is unconstrained here — that is the whole point of starting a
 * new thread, and it is what `requiresNewThreadForModelChange` sends you here
 * for.
 */
class NewThreadViewModel(
    private val repository: ThreadsRepository,
) : ViewModel() {

    private val form = MutableStateFlow(NewThreadForm())

    val uiState: StateFlow<NewThreadUiState> =
        combine(repository.shell, repository.catalog, form) { shell, catalog, current ->
            val projects = shell.projects.values.sortedBy { it.title }

            // Defaults are resolved against live data rather than stored, so a
            // catalogue that arrives after the screen opens still lands.
            val projectId = current.projectId
                ?: projects.firstOrNull()?.id
            val selection = current.modelSelection
                ?: catalog.defaultSelection()

            NewThreadUiState(
                projects = projects,
                catalog = catalog,
                projectId = projectId,
                message = current.message,
                modelSelection = selection,
                selectedModel = catalog.optionFor(selection),
                optionDescriptors = catalog.optionFor(selection)?.model
                    ?.resolveOptionDescriptors(selection?.options)
                    .orEmpty(),
                runtimeMode = current.runtimeMode,
                interactionMode = current.interactionMode,
                isSubmitting = current.isSubmitting,
                error = current.error,
                createdThreadId = current.createdThreadId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = NewThreadUiState(),
        )

    fun onProjectSelected(projectId: ProjectId) {
        form.value = form.value.copy(projectId = projectId, error = null)
    }

    fun onMessageChanged(value: String) {
        form.value = form.value.copy(message = value, error = null)
    }

    fun onModelSelected(option: ModelOption) {
        form.value = form.value.copy(modelSelection = option.selection, error = null)
    }

    /** Changes one per-model tunable before the thread exists. */
    fun onModelOptionSelected(id: String, value: JsonPrimitive) {
        val state = uiState.value
        val selection = state.modelSelection ?: return
        val next = state.optionDescriptors.applyOptionSelection(id, value) ?: return
        form.value = form.value.copy(modelSelection = selection.copy(options = next))
    }

    fun onRuntimeModeSelected(mode: String) {
        form.value = form.value.copy(runtimeMode = mode)
    }

    fun onInteractionModeSelected(mode: String) {
        form.value = form.value.copy(interactionMode = mode)
    }

    /**
     * Creates the thread by starting its first turn.
     *
     * On success [NewThreadUiState.createdThreadId] is set and the host
     * navigates to it — the thread exists server-side before the shell
     * subscription reports it, so navigation must not wait on the list.
     */
    fun create() {
        val state = uiState.value
        val projectId = state.projectId
        val selection = state.modelSelection

        val problem = when {
            projectId == null -> "Choose a project."
            selection == null -> "No models are available on this environment."
            state.message.isBlank() -> "Write a first message."
            else -> null
        }
        if (problem != null) {
            form.value = form.value.copy(error = problem)
            return
        }
        if (state.isSubmitting) return

        viewModelScope.launch {
            form.value = form.value.copy(isSubmitting = true, error = null)

            val result = repository.startThread(
                projectId = projectId!!,
                text = state.message,
                modelSelection = selection!!,
                runtimeMode = state.runtimeMode,
                interactionMode = state.interactionMode,
            )

            form.value = result.fold(
                onSuccess = { form.value.copy(isSubmitting = false, createdThreadId = it) },
                onFailure = {
                    form.value.copy(
                        isSubmitting = false,
                        error = it.message ?: "Could not create the thread.",
                    )
                },
            )
        }
    }

    /** Clears the navigation signal once the host has consumed it. */
    fun onNavigated() {
        form.value = form.value.copy(createdThreadId = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Immutable
private data class NewThreadForm(
    val projectId: ProjectId? = null,
    val message: String = "",
    val modelSelection: ModelSelection? = null,
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val createdThreadId: ThreadId? = null,
)

@Immutable
data class NewThreadUiState(
    val projects: List<OrchestrationProjectShell> = emptyList(),
    val catalog: ProviderCatalog = ProviderCatalog(),
    val projectId: ProjectId? = null,
    val message: String = "",
    val modelSelection: ModelSelection? = null,
    val selectedModel: ModelOption? = null,
    val optionDescriptors: List<ProviderOptionDescriptor> = emptyList(),
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val createdThreadId: ThreadId? = null,
) {
    val canCreate: Boolean
        get() = projectId != null && modelSelection != null && message.isNotBlank() && !isSubmitting

    val hasProjects: Boolean get() = projects.isNotEmpty()
}
