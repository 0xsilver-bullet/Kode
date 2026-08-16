package com.silverbullet.kode.feature.connection.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.datastore.EnvironmentCatalogStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentFleet
import com.silverbullet.kode.core.session.EnvironmentHandle
import com.silverbullet.kode.feature.connection.domain.UpdateEnvironmentUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The settings Environments screen: every saved environment, its live
 * connection status, and an accordion editor for label and address.
 *
 * Mirrors `SettingsEnvironmentsRouteScreen` + `ConnectionEnvironmentRow` in T3
 * Code mobile: one row expanded at a time, saving re-registers the connection,
 * removing forgets credentials with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentsViewModel(
    private val fleet: EnvironmentFleet,
    private val environmentStore: EnvironmentCatalogStore,
    private val updateEnvironment: UpdateEnvironmentUseCase,
) : ViewModel() {

    private val expandedId = MutableStateFlow<EnvironmentId?>(null)
    private val editForm = MutableStateFlow(EnvironmentEditForm())

    private val rows: Flow<List<Pair<EnvironmentHandle, ConnectionState>>> =
        fleet.environments.flatMapLatest { handles ->
            val list = handles.orEmpty()
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { handle -> handle.state.map { handle to it } }) { it.toList() }
            }
        }

    val uiState: StateFlow<EnvironmentsUiState> =
        combine(rows, expandedId, editForm) { pairs, expanded, form ->
            EnvironmentsUiState(
                environments = pairs.map { (handle, connection) ->
                    EnvironmentRowState(
                        environmentId = handle.environmentId,
                        label = handle.record.label,
                        displayUrl = handle.record.httpBaseUrl.removeSuffix("/"),
                        status = statusFor(connection),
                        expanded = handle.environmentId == expanded,
                    )
                },
                form = form,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = EnvironmentsUiState(),
        )

    /** One row expanded at a time; expanding seeds the editor from the record. */
    fun toggleExpanded(environmentId: EnvironmentId) {
        if (expandedId.value == environmentId) {
            expandedId.value = null
            return
        }
        val record = fleet.environments.value
            ?.firstOrNull { it.environmentId == environmentId }
            ?.record ?: return
        editForm.value = EnvironmentEditForm(
            label = record.label,
            url = record.httpBaseUrl.removeSuffix("/"),
        )
        expandedId.value = environmentId
    }

    fun onLabelChanged(value: String) {
        editForm.value = editForm.value.copy(label = value, error = null)
    }

    fun onUrlChanged(value: String) {
        editForm.value = editForm.value.copy(url = value, error = null)
    }

    fun save() {
        val environmentId = expandedId.value ?: return
        val form = editForm.value
        if (form.isSaving) return

        viewModelScope.launch {
            editForm.value = form.copy(isSaving = true, error = null)
            val result = updateEnvironment(
                environmentId = environmentId,
                label = form.label,
                url = form.url,
            )
            result.fold(
                onSuccess = {
                    // Collapse on success, as T3 does; the fleet restarts the
                    // supervisor from the changed record on its own.
                    editForm.value = EnvironmentEditForm()
                    expandedId.value = null
                },
                onFailure = { failure ->
                    editForm.value = editForm.value.copy(
                        isSaving = false,
                        error = failure.message ?: "The environment could not be updated.",
                    )
                },
            )
        }
    }

    fun reconnect(environmentId: EnvironmentId) = fleet.retryNow(environmentId)

    /** Confirmation lives in the UI; by the time this runs the user has agreed. */
    fun remove(environmentId: EnvironmentId) {
        if (expandedId.value == environmentId) expandedId.value = null
        viewModelScope.launch { environmentStore.remove(environmentId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** T3 Code's exact status strings, from `connectionStatusText`. */
private fun statusFor(state: ConnectionState): EnvironmentStatus = when (state) {
    ConnectionState.Connecting ->
        EnvironmentStatus("Connecting...", EnvironmentStatusTone.PENDING)

    is ConnectionState.Reconnecting -> EnvironmentStatus(
        "Failed to connect. Reconnecting... Reason: ${state.detail}",
        EnvironmentStatusTone.PENDING,
    )

    is ConnectionState.Connected ->
        EnvironmentStatus("Connected", EnvironmentStatusTone.GOOD)

    ConnectionState.Offline ->
        EnvironmentStatus("Offline", EnvironmentStatusTone.BAD)

    is ConnectionState.Blocked -> EnvironmentStatus(
        "Connection failed. Reason: ${state.reason}",
        EnvironmentStatusTone.BAD,
    )
}

@Immutable
data class EnvironmentsUiState(
    val environments: List<EnvironmentRowState> = emptyList(),
    val form: EnvironmentEditForm = EnvironmentEditForm(),
) {
    val isEmpty: Boolean get() = environments.isEmpty()
}

@Immutable
data class EnvironmentRowState(
    val environmentId: EnvironmentId,
    val label: String,
    val displayUrl: String,
    val status: EnvironmentStatus,
    val expanded: Boolean,
)

@Immutable
data class EnvironmentStatus(val text: String, val tone: EnvironmentStatusTone)

/** Maps to the status dot colour; the palette stays in the UI layer. */
enum class EnvironmentStatusTone { GOOD, PENDING, BAD }

@Immutable
data class EnvironmentEditForm(
    val label: String = "",
    val url: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
)
