package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.core.datastore.EnvironmentCatalogStore
import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ServerConfig
import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.T3EnvironmentClient
import com.silverbullet.kode.core.network.WebSocketRpcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One connected environment: its stored record plus the live connection state
 * its supervisor maintains.
 */
class EnvironmentHandle internal constructor(
    val record: EnvironmentRecord,
    private val supervisor: EnvironmentSupervisor,
    internal val job: Job,
) {
    val environmentId: EnvironmentId get() = record.environmentId

    val state: StateFlow<ConnectionState> get() = supervisor.state
    val session: StateFlow<T3EnvironmentClient?> get() = supervisor.session
    val serverConfig: StateFlow<ServerConfig?> get() = supervisor.serverConfig

    /** Interrupts backoff, or a blocked state, and attempts immediately. */
    fun retryNow() = supervisor.retryNow()
}

/**
 * Owns one [EnvironmentSupervisor] per stored environment.
 *
 * T3 Code mobile supervises every registered connection concurrently — the
 * registry starts supervision when a connection is registered and stops it on
 * removal. This is that reconciliation loop: the persisted catalog is the
 * desired state, and the fleet diffs the running supervisors against it.
 * Nothing above the fleet opens sockets or schedules reconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentFleet(
    private val store: EnvironmentCatalogStore,
    private val authApi: EnvironmentAuthApi,
    private val transport: WebSocketRpcTransport,
    private val networkMonitor: NetworkMonitor,
    private val appLifecycleMonitor: AppLifecycleMonitor,
) {

    private val _environments = MutableStateFlow<List<EnvironmentHandle>?>(null)

    /**
     * Handles in catalog order, or `null` until the persisted catalog has been
     * read. The `null` phase matters: an empty list means "show onboarding",
     * and flashing onboarding while DataStore is still loading would be wrong.
     */
    val environments: StateFlow<List<EnvironmentHandle>?> = _environments.asStateFlow()

    /** Runs the fleet for the lifetime of [scope]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            store.environments.collect { records -> reconcile(scope, records) }
        }
    }

    /**
     * The handle for one environment, tracking replacement: editing a record
     * restarts its supervisor, and collectors must move to the new handle
     * rather than observe the cancelled one.
     */
    fun handleFor(environmentId: EnvironmentId): Flow<EnvironmentHandle?> =
        environments
            .map { handles -> handles?.firstOrNull { it.environmentId == environmentId } }
            .distinctUntilChanged()

    /** The live client for one environment, `null` while it has no session. */
    fun sessionFor(environmentId: EnvironmentId): Flow<T3EnvironmentClient?> =
        handleFor(environmentId).flatMapLatest { handle ->
            handle?.session ?: flowOf(null)
        }

    fun serverConfigFor(environmentId: EnvironmentId): Flow<ServerConfig?> =
        handleFor(environmentId).flatMapLatest { handle ->
            handle?.serverConfig ?: flowOf(null)
        }

    /** Synchronous read for command dispatch; `null` when not connected. */
    fun sessionNow(environmentId: EnvironmentId): T3EnvironmentClient? =
        _environments.value
            ?.firstOrNull { it.environmentId == environmentId }
            ?.session?.value

    /**
     * The stored record for one environment, for callers that need its address
     * rather than its session — resolving a signed asset URL against the
     * environment's HTTP base, for instance.
     */
    fun recordNow(environmentId: EnvironmentId): EnvironmentRecord? =
        _environments.value
            ?.firstOrNull { it.environmentId == environmentId }
            ?.record

    fun retryNow(environmentId: EnvironmentId) {
        _environments.value
            ?.firstOrNull { it.environmentId == environmentId }
            ?.retryNow()
    }

    /**
     * Diffs running supervisors against the persisted catalog. Called only from
     * the single collector in [start], so it needs no synchronisation.
     *
     * A changed record (new label, new address, new credential) cancels the old
     * supervisor and starts a fresh one — the same "re-register replaces the
     * connection" rule T3 Code applies when a bearer environment is edited.
     */
    private fun reconcile(scope: CoroutineScope, records: List<EnvironmentRecord>) {
        val current = _environments.value.orEmpty().associateBy { it.environmentId }
        val desired = records.associateBy { it.environmentId }

        // Covers both removal (no desired record) and change (different one).
        current.values
            .filter { desired[it.environmentId] != it.record }
            .forEach { it.job.cancel() }

        _environments.value = records.map { record ->
            val existing = current[record.environmentId]
            if (existing != null && existing.record == record) {
                existing
            } else {
                val supervisor = EnvironmentSupervisor(
                    record = record,
                    authApi = authApi,
                    transport = transport,
                    networkMonitor = networkMonitor,
                    appLifecycleMonitor = appLifecycleMonitor,
                )
                EnvironmentHandle(
                    record = record,
                    supervisor = supervisor,
                    job = scope.launch { supervisor.run() },
                )
            }
        }
    }
}
