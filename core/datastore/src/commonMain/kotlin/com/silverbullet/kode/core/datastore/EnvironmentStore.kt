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
 * One paired environment plus the credential that reaches it.
 *
 * The counterpart of a `BearerConnectionRegistration` in
 * `packages/client-runtime/src/connection/catalog.ts`, reduced to the fields a
 * bearer-only client needs.
 */
@Serializable
data class EnvironmentRecord(
    val environmentId: EnvironmentId,
    val label: String,
    val httpBaseUrl: String,
    val wsBaseUrl: String,
    val accessToken: String,
)

/**
 * Persistence for the paired environment.
 *
 * T3 Code supports a catalog of many environments; we start with a single
 * record because v1 pairs with one desktop. The API is already
 * nullable-and-replaceable so growing into a list is additive.
 */
interface EnvironmentStore {
    val environment: Flow<EnvironmentRecord?>

    suspend fun save(record: EnvironmentRecord)

    /**
     * Forgets the environment and everything derived from it. T3 Code treats
     * explicit removal as clearing credentials and caches together, so this is
     * the only supported way to unpair.
     */
    suspend fun clear()
}

class DataStoreEnvironmentStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EnvironmentStore {

    override val environment: Flow<EnvironmentRecord?> =
        dataStore.data.map { preferences ->
            preferences[ENVIRONMENT_KEY]?.let { encoded ->
                // A record written by an older build that no longer decodes is
                // treated as "not paired" rather than crashing the app.
                runCatching { json.decodeFromString<EnvironmentRecord>(encoded) }.getOrNull()
            }
        }

    override suspend fun save(record: EnvironmentRecord) {
        dataStore.edit { preferences ->
            preferences[ENVIRONMENT_KEY] = json.encodeToString(record)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(ENVIRONMENT_KEY) }
    }

    private companion object {
        val ENVIRONMENT_KEY = stringPreferencesKey("paired_environment")
    }
}
