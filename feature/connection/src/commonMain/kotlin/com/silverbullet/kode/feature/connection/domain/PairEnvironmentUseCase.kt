package com.silverbullet.kode.feature.connection.domain

import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.datastore.EnvironmentStore
import com.silverbullet.kode.core.network.ClientPresentation
import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.PairingLinkResolver
import com.silverbullet.kode.core.network.PairingTarget

/**
 * Turns a pairing link into a persisted, connectable environment.
 *
 * Mirrors `preparePairingRegistration` in
 * `packages/client-runtime/src/connection/onboarding.ts`: resolve the link,
 * identify the server, then exchange the one-time credential for a durable
 * bearer session. The one-time credential is never stored — only the exchanged
 * token is.
 */
class PairEnvironmentUseCase(
    private val authApi: EnvironmentAuthApi,
    private val environmentStore: EnvironmentStore,
    private val clientPresentation: ClientPresentation = ClientPresentation.Default,
) {

    suspend fun fromPairingUrl(pairingUrl: String): Result<EnvironmentRecord> =
        pair { PairingLinkResolver.fromPairingUrl(pairingUrl) }

    suspend fun fromHostAndCode(host: String, code: String): Result<EnvironmentRecord> =
        pair { PairingLinkResolver.fromHostAndCode(host, code) }

    private suspend fun pair(resolve: () -> PairingTarget): Result<EnvironmentRecord> =
        runCatching {
            val target = resolve()

            // Identify the server before spending the one-time credential, so a
            // wrong address fails with a useful message instead of burning it.
            val descriptor = authApi.fetchDescriptor(target.httpBaseUrl)
            val access = authApi.exchangeBootstrapCredential(
                httpBaseUrl = target.httpBaseUrl,
                credential = target.credential,
                client = clientPresentation,
            )

            EnvironmentRecord(
                environmentId = descriptor.environmentId,
                label = descriptor.label,
                httpBaseUrl = target.httpBaseUrl,
                wsBaseUrl = target.wsBaseUrl,
                accessToken = access.accessToken,
            ).also { environmentStore.save(it) }
        }
}
