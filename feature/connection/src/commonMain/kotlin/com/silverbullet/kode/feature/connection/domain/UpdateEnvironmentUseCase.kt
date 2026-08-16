package com.silverbullet.kode.feature.connection.domain

import com.silverbullet.kode.core.datastore.EnvironmentCatalogStore
import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.network.PairingLinkResolver
import kotlinx.coroutines.flow.first

/**
 * Edits a saved environment's label and address.
 *
 * Mirrors `ConnectionOnboarding.updateBearer`: the stored credential and
 * environment id are reused untouched — editing is a rename/re-address, never a
 * re-pair — and the websocket base is re-derived from the normalized HTTP base.
 * Persisting the changed record is what restarts that environment's supervisor.
 */
class UpdateEnvironmentUseCase(
    private val environmentStore: EnvironmentCatalogStore,
) {

    suspend operator fun invoke(
        environmentId: EnvironmentId,
        label: String,
        url: String,
    ): Result<EnvironmentRecord> = runCatching {
        val existing = environmentStore.environments.first()
            .firstOrNull { it.environmentId == environmentId }
            ?: throw IllegalArgumentException("Only saved environments can be edited.")

        val trimmedLabel = label.trim()
        if (trimmedLabel.isEmpty()) {
            throw IllegalArgumentException("Environment label cannot be empty.")
        }

        val httpBaseUrl = runCatching { PairingLinkResolver.normalizeBaseUrl(url) }
            .getOrElse { throw IllegalArgumentException("The environment URL is invalid.") }

        existing.copy(
            label = trimmedLabel,
            httpBaseUrl = httpBaseUrl,
            wsBaseUrl = PairingLinkResolver.deriveWsBaseUrl(httpBaseUrl),
        ).also { environmentStore.upsert(it) }
    }
}
