package com.silverbullet.kode.feature.voice.domain

import com.silverbullet.kode.core.datastore.VoiceBindingRecord
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.voice.contract.VoicePairingLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun VoiceBindingStore.bindingFor(environmentId: EnvironmentId): Flow<VoiceBindingRecord?> =
    bindings
        .map { records -> records.firstOrNull { it.environmentId == environmentId } }
        .distinctUntilChanged()

/**
 * Pairs a voice server and binds it to an environment: identify via the well-known
 * descriptor (refusing to spend the one-time code on something that is not a voice
 * server), redeem the code for a bearer token, persist the binding.
 */
class PairVoiceServerUseCase(
    private val api: VoiceServerApi,
    private val store: VoiceBindingStore,
) {
    suspend fun fromPairingUrl(environmentId: EnvironmentId, pairingUrl: String): Result<VoiceBindingRecord> {
        val parsed = VoicePairingLink.parse(pairingUrl)
            ?: return Result.failure(VoiceServerException("That is not a voice pairing link."))
        return pair(environmentId, parsed.baseUrl, parsed.code)
    }

    suspend fun pair(
        environmentId: EnvironmentId,
        baseUrl: String,
        code: String,
    ): Result<VoiceBindingRecord> = runCatching {
        val normalized = VoicePairingLink.normalizeBaseUrl(baseUrl)
        val descriptor = api.fetchDescriptor(normalized)
        val paired = api.pair(
            baseUrl = normalized,
            code = code,
            clientLabel = "Kode",
            clientOs = "android",
        )
        val record = VoiceBindingRecord(
            environmentId = environmentId,
            serverUrl = normalized,
            serverId = descriptor.serverId,
            label = paired.label,
            accessToken = paired.accessToken,
        )
        store.upsert(record)
        record
    }
}
