package com.silverbullet.kode.feature.connection.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.datastore.EnvironmentStore
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentSupervisor
import com.silverbullet.kode.feature.connection.domain.PairEnvironmentUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presentation state for the connection screen.
 *
 * Deliberately platform-free: this is `androidx.lifecycle.ViewModel` from the
 * multiplatform artifact, so a SwiftUI screen can bind to the same instance
 * without a parallel iOS view model.
 */
class ConnectionViewModel(
    private val supervisor: EnvironmentSupervisor,
    private val pairEnvironment: PairEnvironmentUseCase,
    private val environmentStore: EnvironmentStore,
) : ViewModel() {

    private val pairingForm = MutableStateFlow(PairingFormState())

    val uiState: StateFlow<ConnectionUiState> =
        combine(supervisor.state, pairingForm) { connection, form ->
            ConnectionUiState(connection = connection, form = form)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ConnectionUiState(),
        )

    fun onPairingUrlChanged(value: String) {
        pairingForm.value = pairingForm.value.copy(pairingUrl = value, error = null)
    }

    /**
     * Exchanges the pairing link for a stored session. Success needs no
     * navigation: persisting the record is what wakes the supervisor, which
     * then drives the connection state.
     */
    fun pair() {
        val url = pairingForm.value.pairingUrl.trim()
        if (url.isEmpty() || pairingForm.value.isSubmitting) return

        viewModelScope.launch {
            pairingForm.value = pairingForm.value.copy(isSubmitting = true, error = null)
            val result = pairEnvironment.fromPairingUrl(url)
            pairingForm.value = result.fold(
                onSuccess = { PairingFormState() },
                onFailure = { failure ->
                    pairingForm.value.copy(
                        isSubmitting = false,
                        error = failure.message ?: "Pairing failed.",
                    )
                },
            )
        }
    }

    fun retry() = supervisor.retryNow()

    /** Forgets the environment and every credential derived from it. */
    fun unpair() {
        viewModelScope.launch { environmentStore.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class ConnectionUiState(
    val connection: ConnectionState = ConnectionState.Unpaired,
    val form: PairingFormState = PairingFormState(),
)

data class PairingFormState(
    val pairingUrl: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)
