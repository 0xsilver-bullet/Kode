package com.silverbullet.kode.feature.connection.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.feature.connection.domain.PairEnvironmentUseCase
import com.silverbullet.kode.feature.connection.domain.PairingInput
import com.silverbullet.kode.core.common.QrCodeScanner
import com.silverbullet.kode.core.common.QrScanOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Add Environment form: a host, a pairing code, and a QR shortcut that
 * fills both.
 *
 * Used by two hosts — first-run onboarding (when the catalog is empty) and the
 * settings "Add Environment" screen — so the pairing rules cannot drift between
 * them. Mirrors `ConnectionsNewRouteScreen` in T3 Code mobile: scanning fills
 * the fields but never auto-connects, and submitting rebuilds one pairing URL
 * so typed, pasted and scanned input all take the same validation path.
 */
class AddEnvironmentViewModel(
    private val pairEnvironment: PairEnvironmentUseCase,
    private val qrScanner: QrCodeScanner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AddEnvironmentUiState(canScanQr = qrScanner.isAvailable),
    )
    val uiState: StateFlow<AddEnvironmentUiState> = _uiState.asStateFlow()

    /**
     * Pasting a full pairing URL splits it into host and code. Anything without
     * a recognizable token is kept verbatim, so normal typing is never mangled
     * by a partial parse.
     */
    fun onHostChanged(value: String) {
        val parsed = PairingInput.parsePairingUrl(value)
        _uiState.value = if (parsed.code.isNotEmpty()) {
            _uiState.value.copy(host = parsed.host, code = parsed.code, error = null)
        } else {
            _uiState.value.copy(host = value, error = null)
        }
    }

    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(code = value, error = null)
    }

    /**
     * Exchanges the form for a stored environment. Success needs no navigation
     * signal beyond [AddEnvironmentUiState.added]: persisting the record wakes
     * the fleet, which starts connecting on its own.
     */
    fun submit() {
        val state = _uiState.value
        if (state.host.isBlank() || state.isSubmitting) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            val pairingUrl = PairingInput.buildPairingUrl(state.host, state.code)
            val result = pairEnvironment.fromPairingUrl(pairingUrl)
            _uiState.value = result.fold(
                onSuccess = {
                    AddEnvironmentUiState(canScanQr = qrScanner.isAvailable, added = true)
                },
                onFailure = { failure ->
                    _uiState.value.copy(
                        isSubmitting = false,
                        error = failure.message ?: "Failed to pair with the environment.",
                    )
                },
            )
        }
    }

    /** Scans a QR code and fills the form. Deliberately does not submit. */
    fun scanQr() {
        if (_uiState.value.isScanning) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val outcome = qrScanner.scan()
            _uiState.value = _uiState.value.copy(isScanning = false)

            when (outcome) {
                is QrScanOutcome.Scanned -> {
                    val fields = runCatching {
                        PairingInput.parsePairingUrl(
                            PairingInput.extractPairingUrlFromQrPayload(outcome.payload),
                        )
                    }.getOrNull()

                    _uiState.value = if (fields == null || fields.host.isEmpty()) {
                        _uiState.value.copy(error = "Scanned QR code was not recognized.")
                    } else {
                        _uiState.value.copy(host = fields.host, code = fields.code, error = null)
                    }
                }

                QrScanOutcome.Cancelled -> Unit

                is QrScanOutcome.Failed ->
                    _uiState.value = _uiState.value.copy(error = outcome.message)
            }
        }
    }

    /** Clears the navigation signal once the host has consumed it. */
    fun onAddedConsumed() {
        _uiState.value = _uiState.value.copy(added = false)
    }
}

@Immutable
data class AddEnvironmentUiState(
    val host: String = "",
    val code: String = "",
    val isSubmitting: Boolean = false,
    val isScanning: Boolean = false,
    val canScanQr: Boolean = false,
    val error: String? = null,
    val added: Boolean = false,
) {
    val canSubmit: Boolean get() = host.isNotBlank() && !isSubmitting
}
