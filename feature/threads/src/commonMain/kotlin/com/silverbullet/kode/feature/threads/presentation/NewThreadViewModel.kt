package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.ImagePickOutcome
import com.silverbullet.kode.core.common.ImagePicker
import com.silverbullet.kode.core.model.AttachmentLimits
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.InteractionMode
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.core.model.RuntimeMode
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.DraftAttachment
import com.silverbullet.kode.feature.threads.domain.ModelOption
import com.silverbullet.kode.feature.threads.domain.ProviderCatalog
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.domain.appendPicked
import com.silverbullet.kode.feature.threads.domain.applyOptionSelection
import com.silverbullet.kode.feature.threads.domain.attachmentCapReason
import com.silverbullet.kode.feature.threads.domain.remainingAttachmentSlots
import com.silverbullet.kode.feature.threads.domain.removeAttachment
import com.silverbullet.kode.feature.threads.domain.resolveOptionDescriptors
import com.silverbullet.kode.feature.threads.domain.toUploads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Composes a new thread: which environment and project it belongs to, how the
 * agent is configured, and the first message that starts it.
 *
 * There is no title field — as in T3 Code's mobile client, the title is
 * derived from the first message and refined server-side from `titleSeed`.
 *
 * The environment defaults to the first connected one; picking another resets
 * project and model, because both are meaningless off their own server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewThreadViewModel(
    private val repository: ThreadsRepository,
    private val imagePicker: ImagePicker,
    private val idGenerator: IdGenerator,
) : ViewModel() {

    private val form = MutableStateFlow(NewThreadForm())

    /**
     * The environment the form is composing against: the explicit choice while
     * it still exists, else the first connected environment, else the first
     * saved one — the same fallback ladder T3's new-task flow applies.
     */
    private val effectiveEnvironmentId = combine(repository.shells, form) { shells, current ->
        current.environmentId?.takeIf { id -> shells.any { it.environmentId == id } }
            ?: shells.firstOrNull { it.isConnected }?.environmentId
            ?: shells.firstOrNull()?.environmentId
    }.distinctUntilChanged()

    private val catalog = effectiveEnvironmentId.flatMapLatest { environmentId ->
        if (environmentId == null) flowOf(ProviderCatalog()) else repository.catalog(environmentId)
    }

    val uiState: StateFlow<NewThreadUiState> =
        combine(
            repository.shells,
            effectiveEnvironmentId,
            catalog,
            form,
        ) { shells, environmentId, catalog, current ->
            val environment = shells.firstOrNull { it.environmentId == environmentId }
            val projects = environment?.shell?.projects?.values.orEmpty().sortedBy { it.title }

            // Defaults are resolved against live data rather than stored, so a
            // catalogue that arrives after the screen opens still lands.
            val projectId = current.projectId?.takeIf { id -> projects.any { it.id == id } }
                ?: projects.firstOrNull()?.id
            val selection = current.modelSelection
                ?: catalog.defaultSelection()

            NewThreadUiState(
                environments = shells.map {
                    EnvironmentOption(
                        environmentId = it.environmentId,
                        label = it.label,
                        isConnected = it.isConnected,
                    )
                },
                environmentId = environmentId,
                projects = projects,
                catalog = catalog,
                projectId = projectId,
                message = current.message,
                attachments = current.attachments,
                canAttach = imagePicker.isAvailable,
                modelSelection = selection,
                selectedModel = catalog.optionFor(selection),
                optionDescriptors = catalog.optionFor(selection)?.model
                    ?.resolveOptionDescriptors(selection?.options)
                    .orEmpty(),
                runtimeMode = current.runtimeMode,
                interactionMode = current.interactionMode,
                isSubmitting = current.isSubmitting,
                error = current.error,
                created = current.created,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = NewThreadUiState(),
        )

    /** Project and model reset: both belong to the machine that hosts them. */
    fun onEnvironmentSelected(environmentId: EnvironmentId) {
        form.value = form.value.copy(
            environmentId = environmentId,
            projectId = null,
            modelSelection = null,
            error = null,
        )
    }

    fun onProjectSelected(projectId: ProjectId) {
        form.value = form.value.copy(projectId = projectId, error = null)
    }

    fun onMessageChanged(value: String) {
        form.value = form.value.copy(message = value, error = null)
    }

    /** Stages images for the first message. Mirrors the thread composer's picker. */
    fun onPickImages() {
        val staged = form.value.attachments
        staged.attachmentCapReason()?.let { reason ->
            form.value = form.value.copy(error = reason)
            return
        }

        viewModelScope.launch {
            val outcome = imagePicker.pick(
                maxCount = staged.remainingAttachmentSlots(),
                maxBytes = AttachmentLimits.MAX_IMAGE_BYTES,
                supportedMimeTypes = AttachmentLimits.SUPPORTED_IMAGE_MIME_TYPES,
            )
            form.value = when (outcome) {
                is ImagePickOutcome.Picked -> form.value.copy(
                    attachments = form.value.attachments
                        .appendPicked(outcome.images, idGenerator::newId),
                    error = outcome.warning,
                )

                ImagePickOutcome.Cancelled -> form.value
                is ImagePickOutcome.Failed -> form.value.copy(error = outcome.message)
            }
        }
    }

    fun onRemoveAttachment(attachmentId: String) {
        form.value = form.value.copy(
            attachments = form.value.attachments.removeAttachment(attachmentId),
            error = null,
        )
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
     * On success [NewThreadUiState.created] is set and the host navigates to
     * it — the thread exists server-side before the shell subscription reports
     * it, so navigation must not wait on the list.
     */
    fun create() {
        val state = uiState.value
        val environmentId = state.environmentId
        val projectId = state.projectId
        val selection = state.modelSelection

        val problem = when {
            environmentId == null -> "Connect an environment first."
            projectId == null -> "Choose a project."
            selection == null -> "No models are available on this environment."
            state.message.isBlank() && state.attachments.isEmpty() ->
                "Write a first message."
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
                environmentId = environmentId!!,
                projectId = projectId!!,
                text = state.message,
                modelSelection = selection!!,
                runtimeMode = state.runtimeMode,
                interactionMode = state.interactionMode,
                attachments = state.attachments.toUploads(),
            )

            form.value = result.fold(
                onSuccess = {
                    form.value.copy(
                        isSubmitting = false,
                        created = CreatedThread(environmentId, it),
                    )
                },
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
        form.value = form.value.copy(created = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Immutable
private data class NewThreadForm(
    val environmentId: EnvironmentId? = null,
    val projectId: ProjectId? = null,
    val message: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val modelSelection: ModelSelection? = null,
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val created: CreatedThread? = null,
)

/** Where the created thread lives, so the host can navigate to it. */
@Immutable
data class CreatedThread(val environmentId: EnvironmentId, val threadId: ThreadId)

@Immutable
data class EnvironmentOption(
    val environmentId: EnvironmentId,
    val label: String,
    val isConnected: Boolean,
)

@Immutable
data class NewThreadUiState(
    val environments: List<EnvironmentOption> = emptyList(),
    val environmentId: EnvironmentId? = null,
    val projects: List<OrchestrationProjectShell> = emptyList(),
    val catalog: ProviderCatalog = ProviderCatalog(),
    val projectId: ProjectId? = null,
    val message: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    /** False on hosts with no image picker, which hides the attach affordance. */
    val canAttach: Boolean = false,
    val modelSelection: ModelSelection? = null,
    val selectedModel: ModelOption? = null,
    val optionDescriptors: List<ProviderOptionDescriptor> = emptyList(),
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val created: CreatedThread? = null,
) {
    val canCreate: Boolean
        get() = environmentId != null && projectId != null && modelSelection != null &&
            (message.isNotBlank() || attachments.isNotEmpty()) && !isSubmitting

    /** False once the per-message cap is reached, which disables the picker. */
    val canAttachMore: Boolean get() = attachments.size < AttachmentLimits.MAX_ATTACHMENTS

    val hasProjects: Boolean get() = projects.isNotEmpty()

    /** T3 shows the environment control as static until there is a choice. */
    val canPickEnvironment: Boolean get() = environments.size > 1

    val selectedEnvironmentLabel: String
        get() = environments.firstOrNull { it.environmentId == environmentId }?.label
            ?: "Environment"
}
