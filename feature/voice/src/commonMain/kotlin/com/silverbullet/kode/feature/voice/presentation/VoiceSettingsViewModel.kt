package com.silverbullet.kode.feature.voice.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.QrCodeScanner
import com.silverbullet.kode.core.common.QrScanOutcome
import com.silverbullet.kode.core.datastore.EnvironmentCatalogStore
import com.silverbullet.kode.core.datastore.VoiceBindingRecord
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.core.datastore.VoiceSettingsStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.voice.domain.PairVoiceServerUseCase
import com.silverbullet.kode.feature.voice.domain.VoiceServerApi
import com.silverbullet.kode.voice.contract.VoiceProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Voice: the refinement toggle plus one binding row per paired environment.
 *
 * Discovery is deliberately assistive, not magic: probing the environment's own host on
 * the default voice port can prefill the server URL, but pairing always requires a live
 * one-time code (scanned or typed), because possession of the code is the authorization.
 */
class VoiceSettingsViewModel(
    environmentStore: EnvironmentCatalogStore,
    private val bindingStore: VoiceBindingStore,
    private val settingsStore: VoiceSettingsStore,
    private val api: VoiceServerApi,
    private val pairUseCase: PairVoiceServerUseCase,
    private val qrScanner: QrCodeScanner,
) : ViewModel() {

    private val forms = MutableStateFlow<Map<EnvironmentId, VoiceBindForm>>(emptyMap())

    val canScanQr: Boolean get() = qrScanner.isAvailable

    val state: StateFlow<VoiceSettingsUiState> = combine(
        environmentStore.environments,
        bindingStore.bindings,
        settingsStore.settings,
        forms,
    ) { environments, bindings, settings, forms ->
        VoiceSettingsUiState(
            refinementEnabled = settings.refinementEnabled,
            rows = environments.map { environment ->
                EnvironmentVoiceRow(
                    environmentId = environment.environmentId,
                    environmentLabel = environment.label,
                    environmentHost = extractHost(environment.httpBaseUrl),
                    binding = bindings.firstOrNull { it.environmentId == environment.environmentId },
                    form = forms[environment.environmentId] ?: VoiceBindForm(),
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VoiceSettingsUiState(),
    )

    fun toggleRefinement() {
        viewModelScope.launch {
            settingsStore.update { it.copy(refinementEnabled = !it.refinementEnabled) }
        }
    }

    fun onServerUrlChanged(environmentId: EnvironmentId, value: String) {
        updateForm(environmentId) { it.copy(serverUrl = value, error = null) }
    }

    fun onCodeChanged(environmentId: EnvironmentId, value: String) {
        updateForm(environmentId) { it.copy(code = value, error = null) }
    }

    /**
     * Best-effort discovery: the voice server for an environment usually runs on the
     * same machine, so try that host on the default port and prefill on success.
     */
    fun probe(environmentId: EnvironmentId) {
        val environmentHost = state.value.rows
            .firstOrNull { it.environmentId == environmentId }?.environmentHost ?: return
        val candidate = "http://$environmentHost:${DEFAULT_VOICE_PORT}"

        updateForm(environmentId) { it.copy(probing = true, error = null) }
        viewModelScope.launch {
            val descriptor = runCatching { api.fetchDescriptor(candidate) }.getOrNull()
            updateForm(environmentId) { form ->
                when {
                    descriptor != null -> form.copy(
                        probing = false,
                        serverUrl = candidate,
                        discoveredLabel = descriptor.label,
                    )
                    else -> form.copy(
                        probing = false,
                        error = "Nothing answering at $candidate — enter the server address or scan its QR.",
                    )
                }
            }
        }
    }

    fun scanQr(environmentId: EnvironmentId) {
        viewModelScope.launch {
            when (val outcome = qrScanner.scan()) {
                is QrScanOutcome.Scanned -> pairFromUrl(environmentId, outcome.payload)
                is QrScanOutcome.Failed ->
                    updateForm(environmentId) { it.copy(error = outcome.message) }
                QrScanOutcome.Cancelled -> Unit
            }
        }
    }

    /** Pairs from a pasted link, or from the URL + code fields. */
    fun pair(environmentId: EnvironmentId) {
        val form = forms.value[environmentId] ?: VoiceBindForm()
        val pasted = form.serverUrl.trim()
        if (pasted.contains("#code=")) {
            pairFromUrl(environmentId, pasted)
            return
        }
        if (pasted.isEmpty() || form.code.isBlank()) {
            updateForm(environmentId) { it.copy(error = "Enter the server address and a pairing code, or scan the QR.") }
            return
        }
        runPairing(environmentId) { pairUseCase.pair(environmentId, pasted, form.code.trim()) }
    }

    fun unbind(environmentId: EnvironmentId) {
        viewModelScope.launch { bindingStore.remove(environmentId) }
    }

    private fun pairFromUrl(environmentId: EnvironmentId, url: String) {
        runPairing(environmentId) { pairUseCase.fromPairingUrl(environmentId, url) }
    }

    private fun runPairing(
        environmentId: EnvironmentId,
        block: suspend () -> Result<VoiceBindingRecord>,
    ) {
        updateForm(environmentId) { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            val result = try {
                block()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                Result.failure(cause)
            }
            result.fold(
                onSuccess = {
                    updateForm(environmentId) { VoiceBindForm() }
                },
                onFailure = { failure ->
                    updateForm(environmentId) {
                        it.copy(pairing = false, error = failure.message ?: "Pairing failed.")
                    }
                },
            )
        }
    }

    private fun updateForm(environmentId: EnvironmentId, transform: (VoiceBindForm) -> VoiceBindForm) {
        forms.value = forms.value + (environmentId to transform(forms.value[environmentId] ?: VoiceBindForm()))
    }

    private fun extractHost(httpBaseUrl: String): String? =
        runCatching {
            httpBaseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        /** [com.silverbullet.kode.voice.contract.VoiceProtocol] has no port opinion; this mirrors the server's default. */
        const val DEFAULT_VOICE_PORT = 8484

        init {
            // Compile-time tie to the contract so the constant is not free-floating.
            check(VoiceProtocol.PROTOCOL_VERSION >= 1)
        }
    }
}

@Immutable
data class VoiceSettingsUiState(
    val refinementEnabled: Boolean = true,
    val rows: List<EnvironmentVoiceRow> = emptyList(),
)

@Immutable
data class EnvironmentVoiceRow(
    val environmentId: EnvironmentId,
    val environmentLabel: String,
    val environmentHost: String?,
    val binding: VoiceBindingRecord?,
    val form: VoiceBindForm,
)

@Immutable
data class VoiceBindForm(
    val serverUrl: String = "",
    val code: String = "",
    val discoveredLabel: String? = null,
    val probing: Boolean = false,
    val pairing: Boolean = false,
    val error: String? = null,
)
