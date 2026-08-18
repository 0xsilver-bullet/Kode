package com.silverbullet.kode.feature.voice.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.datastore.VoiceSettingsStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.feature.voice.domain.AudioRecorder
import com.silverbullet.kode.feature.voice.domain.MicPermission
import com.silverbullet.kode.feature.voice.domain.Transcript
import com.silverbullet.kode.feature.voice.domain.VoiceLiveSession
import com.silverbullet.kode.feature.voice.domain.VoiceServerApi
import com.silverbullet.kode.feature.voice.domain.bindingFor
import com.silverbullet.kode.voice.contract.VoiceCompleted
import com.silverbullet.kode.voice.contract.VoiceError
import com.silverbullet.kode.voice.contract.VoiceReady
import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import com.silverbullet.kode.voice.contract.VoiceTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The voice prompt dialog's lifecycle, from mic-open to a dispatched turn.
 *
 * Two invariants from the spec are enforced here rather than in the UI:
 *  - the dialog never dismisses itself — every exit is an explicit user action
 *    ([cancel], [decline], or a successful [accept]/[sendEdited]);
 *  - the live transcript never touches the thread composer; the only way text leaves
 *    this dialog is the [sendPrompt] callback on accept.
 *
 * [amplitude] is a separate flow on purpose: it changes on every audio chunk (~25/s)
 * and must only recompose the indicator, never the transcript text.
 */
class VoicePromptViewModel(
    private val environmentId: EnvironmentId,
    private val projectDir: String?,
    private val recentMessages: () -> List<VoiceThreadMessage>,
    private val sendPrompt: suspend (String) -> Result<Unit>,
    private val bindingStore: VoiceBindingStore,
    private val settingsStore: VoiceSettingsStore,
    private val api: VoiceServerApi,
    private val recorder: AudioRecorder,
    private val permission: MicPermission,
) : ViewModel() {

    private val _state = MutableStateFlow<VoicePromptUiState>(VoicePromptUiState.Idle)
    val state: StateFlow<VoicePromptUiState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    val refinementEnabled: StateFlow<Boolean> = settingsStore.settings
        .map { it.refinementEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    private var sessionJob: Job? = null
    private var audioJob: Job? = null
    private var session: VoiceLiveSession? = null

    fun toggleRefinement() {
        viewModelScope.launch {
            settingsStore.update { it.copy(refinementEnabled = !it.refinementEnabled) }
        }
    }

    /** Idempotent: begins a session only from [VoicePromptUiState.Idle]. */
    fun begin() {
        if (_state.value != VoicePromptUiState.Idle) return
        _state.value = VoicePromptUiState.Connecting

        sessionJob = viewModelScope.launch {
            if (!recorder.isAvailable) {
                _state.value = VoicePromptUiState.Failed("Voice capture is not available on this device.")
                return@launch
            }
            if (!permission.ensure()) {
                _state.value = VoicePromptUiState.PermissionDenied
                return@launch
            }
            val binding = bindingStore.bindingFor(environmentId).first()
            if (binding == null) {
                _state.value = VoicePromptUiState.Failed(
                    "No voice server is bound to this environment. Bind one in Settings → Voice.",
                )
                return@launch
            }

            val live = try {
                api.openSession(binding, VoiceStart(projectDir = projectDir))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                _state.value = VoicePromptUiState.Failed(
                    cause.message ?: "Could not reach the voice server.",
                )
                return@launch
            }
            session = live

            audioJob = launch {
                recorder.record().collect { chunk ->
                    _amplitude.value = chunk.amplitude
                    live.sendAudio(chunk.bytes)
                }
            }

            try {
                live.events.collect { event ->
                    when (event) {
                        is VoiceReady -> _state.value = VoicePromptUiState.Recording(Transcript())
                        is VoiceTranscript -> applyTranscript(event)
                        is VoiceCompleted -> {
                            stopAudio()
                            onCompleted(event.transcript)
                        }
                        is VoiceError -> {
                            stopAudio()
                            _state.value = VoicePromptUiState.Failed(event.message)
                        }
                        else -> Unit
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                stopAudio()
                // A drop mid-review is invisible; only surface transport failures while
                // the session is still producing the transcript.
                if (_state.value.isLive) {
                    _state.value = VoicePromptUiState.Failed(
                        cause.message ?: "Lost the connection to the voice server.",
                    )
                }
            }
        }
    }

    /** The user is done talking: flush the tail and wait for the final transcript. */
    fun stopTalking() {
        val current = _state.value as? VoicePromptUiState.Recording ?: return
        _state.value = VoicePromptUiState.Finalizing(current.transcript)
        // Release the mic right away; the server has all the audio it needs.
        stopAudio()
        viewModelScope.launch { session?.stop() }
    }

    fun accept() {
        val review = _state.value as? VoicePromptUiState.Review ?: return
        dispatch(review.text) { error -> review.copy(isSending = false, error = error) }
    }

    fun edit() {
        val review = _state.value as? VoicePromptUiState.Review ?: return
        _state.value = VoicePromptUiState.Editing(draft = review.text)
    }

    fun onDraftChanged(value: String) {
        val editing = _state.value as? VoicePromptUiState.Editing ?: return
        _state.value = editing.copy(draft = value, error = null)
    }

    fun sendEdited() {
        val editing = _state.value as? VoicePromptUiState.Editing ?: return
        if (editing.draft.isBlank()) return
        dispatch(editing.draft.trim()) { error -> editing.copy(isSending = false, error = error) }
    }

    /** Declining a reviewed prompt discards it and closes the dialog. */
    fun decline() {
        teardown()
        _state.value = VoicePromptUiState.Dismissed
    }

    /** Cancels whatever is in flight — recording, refinement, or review. */
    fun cancel() {
        teardown()
        _state.value = VoicePromptUiState.Dismissed
    }

    /** Host acknowledgement of [VoicePromptUiState.Dismissed]; ready for the next open. */
    fun reset() {
        if (_state.value == VoicePromptUiState.Dismissed) {
            _state.value = VoicePromptUiState.Idle
        }
    }

    fun retry() {
        if (_state.value is VoicePromptUiState.Failed || _state.value == VoicePromptUiState.PermissionDenied) {
            teardown()
            _state.value = VoicePromptUiState.Idle
            begin()
        }
    }

    private fun applyTranscript(event: VoiceTranscript) {
        when (val current = _state.value) {
            is VoicePromptUiState.Recording ->
                _state.value = current.copy(transcript = current.transcript.apply(event))
            is VoicePromptUiState.Finalizing ->
                _state.value = current.copy(transcript = current.transcript.apply(event))
            else -> Unit
        }
    }

    private suspend fun onCompleted(transcript: String) {
        val text = transcript.trim()
        if (text.isEmpty()) {
            _state.value = VoicePromptUiState.Failed("No speech was detected — try again.")
            return
        }
        if (!refinementEnabled.value) {
            _state.value = VoicePromptUiState.Review(text = text, wasRefined = false)
            return
        }

        _state.value = VoicePromptUiState.Refining(text)
        _state.value = try {
            val binding = bindingStore.bindingFor(environmentId).first()
                ?: error("binding disappeared mid-session")
            val refined = api.refine(
                binding = binding,
                request = VoiceRefineRequest(
                    transcript = text,
                    projectDir = projectDir,
                    threadMessages = recentMessages(),
                ),
            )
            VoicePromptUiState.Review(text = refined.refinedText, wasRefined = refined.changed)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            // Refinement is best-effort; the raw transcript is always reviewable.
            VoicePromptUiState.Review(text = text, wasRefined = false, refineFailed = true)
        }
    }

    private fun dispatch(text: String, onFailure: (String) -> VoicePromptUiState) {
        val current = _state.value
        val alreadySending = (current as? VoicePromptUiState.Review)?.isSending == true ||
            (current as? VoicePromptUiState.Editing)?.isSending == true
        if (alreadySending) return

        _state.value = when (current) {
            is VoicePromptUiState.Review -> current.copy(isSending = true, error = null)
            is VoicePromptUiState.Editing -> current.copy(isSending = true, error = null)
            else -> return
        }
        viewModelScope.launch {
            sendPrompt(text).fold(
                onSuccess = {
                    teardown()
                    _state.value = VoicePromptUiState.Dismissed
                },
                onFailure = { failure ->
                    _state.value = onFailure(failure.message ?: "Could not send the prompt.")
                },
            )
        }
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        _amplitude.value = 0f
    }

    private fun teardown() {
        stopAudio()
        sessionJob?.cancel()
        sessionJob = null
        val live = session
        session = null
        if (live != null) {
            viewModelScope.launch {
                withContext(NonCancellable) {
                    runCatching { live.abort() }
                    live.close()
                }
            }
        }
    }

    override fun onCleared() {
        teardown()
    }
}

@Immutable
sealed interface VoicePromptUiState {
    data object Idle : VoicePromptUiState

    data object Connecting : VoicePromptUiState

    data object PermissionDenied : VoicePromptUiState

    /** Mic open, transcript growing. */
    data class Recording(val transcript: Transcript) : VoicePromptUiState

    /** Stop tapped; waiting for the server to flush the tail. */
    data class Finalizing(val transcript: Transcript) : VoicePromptUiState

    data class Refining(val transcript: String) : VoicePromptUiState

    data class Review(
        val text: String,
        val wasRefined: Boolean,
        val refineFailed: Boolean = false,
        val isSending: Boolean = false,
        val error: String? = null,
    ) : VoicePromptUiState

    data class Editing(
        val draft: String,
        val isSending: Boolean = false,
        val error: String? = null,
    ) : VoicePromptUiState

    data class Failed(val message: String) : VoicePromptUiState

    /** Terminal: the host should close the dialog and call [VoicePromptViewModel.reset]. */
    data object Dismissed : VoicePromptUiState

    /** True while the socket is still the source of truth for the transcript. */
    val isLive: Boolean
        get() = this is Connecting || this is Recording || this is Finalizing
}
