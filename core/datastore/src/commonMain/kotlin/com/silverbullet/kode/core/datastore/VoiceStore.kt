package com.silverbullet.kode.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.silverbullet.kode.core.model.EnvironmentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A voice server bound to one paired environment.
 *
 * Deliberately NOT a field on [EnvironmentRecord]: `EnvironmentFleet` reconciles
 * supervisors by record equality, so growing that record would tear down and re-dial the
 * environment's RPC socket every time a voice binding changes.
 */
@Serializable
data class VoiceBindingRecord(
    val environmentId: EnvironmentId,
    /** Normalized base URL with trailing slash, e.g. `https://machine.ts.net:8484/`. */
    val serverUrl: String,
    val serverId: String,
    val label: String,
    val accessToken: String,
)

/** Feature-wide voice settings (not per environment). */
@Serializable
data class VoiceSettings(
    val refinementEnabled: Boolean = true,
)

interface VoiceBindingStore {
    val bindings: Flow<List<VoiceBindingRecord>>

    suspend fun upsert(record: VoiceBindingRecord)

    suspend fun remove(environmentId: EnvironmentId)
}

interface VoiceSettingsStore {
    val settings: Flow<VoiceSettings>

    suspend fun update(transform: (VoiceSettings) -> VoiceSettings)
}

class DataStoreVoiceBindingStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : VoiceBindingStore {

    override val bindings: Flow<List<VoiceBindingRecord>> =
        dataStore.data.map { preferences -> preferences.decodeBindings() }

    override suspend fun upsert(record: VoiceBindingRecord) {
        dataStore.edit { preferences ->
            val current = preferences.decodeBindings()
            val next = if (current.any { it.environmentId == record.environmentId }) {
                current.map { if (it.environmentId == record.environmentId) record else it }
            } else {
                current + record
            }
            preferences[BINDINGS_KEY] = json.encodeToString(next)
        }
    }

    override suspend fun remove(environmentId: EnvironmentId) {
        dataStore.edit { preferences ->
            preferences[BINDINGS_KEY] = json.encodeToString(
                preferences.decodeBindings().filterNot { it.environmentId == environmentId },
            )
        }
    }

    private fun Preferences.decodeBindings(): List<VoiceBindingRecord> =
        this[BINDINGS_KEY]
            ?.let { encoded ->
                runCatching { json.decodeFromString<List<VoiceBindingRecord>>(encoded) }.getOrNull()
            }
            .orEmpty()

    private companion object {
        val BINDINGS_KEY = stringPreferencesKey("voice_bindings")
    }
}

class DataStoreVoiceSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : VoiceSettingsStore {

    override val settings: Flow<VoiceSettings> =
        dataStore.data.map { preferences -> preferences.decodeSettings() }

    override suspend fun update(transform: (VoiceSettings) -> VoiceSettings) {
        dataStore.edit { preferences ->
            preferences[SETTINGS_KEY] = json.encodeToString(transform(preferences.decodeSettings()))
        }
    }

    private fun Preferences.decodeSettings(): VoiceSettings =
        this[SETTINGS_KEY]
            ?.let { encoded -> runCatching { json.decodeFromString<VoiceSettings>(encoded) }.getOrNull() }
            ?: VoiceSettings()

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("voice_settings")
    }
}
