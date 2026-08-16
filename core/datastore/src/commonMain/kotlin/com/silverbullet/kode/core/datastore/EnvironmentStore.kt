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
 * Persistence for the catalog of paired environments.
 *
 * T3 Code mobile keeps a catalog of many environments in one document
 * (`t3code.connection-catalog.v1`); this is the same idea with the same
 * single-document atomicity: every mutation rewrites the whole list inside one
 * DataStore transaction.
 */
interface EnvironmentCatalogStore {
    val environments: Flow<List<EnvironmentRecord>>

    /**
     * Adds the record, or replaces the record with the same environment id in
     * place. Replacing in place keeps the list order stable, so re-pairing an
     * environment does not make it jump to the bottom of the settings screen.
     */
    suspend fun upsert(record: EnvironmentRecord)

    /**
     * Forgets the environment and everything derived from it. T3 Code treats
     * explicit removal as clearing credentials and caches together, so this is
     * the only supported way to unpair.
     */
    suspend fun remove(environmentId: EnvironmentId)
}

class DataStoreEnvironmentCatalogStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EnvironmentCatalogStore {

    override val environments: Flow<List<EnvironmentRecord>> =
        dataStore.data.map { preferences -> preferences.decodeCatalog() }

    override suspend fun upsert(record: EnvironmentRecord) {
        dataStore.edit { preferences ->
            val current = preferences.decodeCatalog()
            val next = if (current.any { it.environmentId == record.environmentId }) {
                current.map { if (it.environmentId == record.environmentId) record else it }
            } else {
                current + record
            }
            preferences.writeCatalog(next)
        }
    }

    override suspend fun remove(environmentId: EnvironmentId) {
        dataStore.edit { preferences ->
            preferences.writeCatalog(
                preferences.decodeCatalog().filterNot { it.environmentId == environmentId },
            )
        }
    }

    /**
     * Reads the catalog, falling back to the single-environment record written
     * by builds that predate multi-environment support. The legacy key is only
     * deleted on the first write, so a downgrade before then loses nothing.
     */
    private fun Preferences.decodeCatalog(): List<EnvironmentRecord> {
        this[CATALOG_KEY]?.let { encoded ->
            // A catalog that no longer decodes is treated as "no environments"
            // rather than crashing the app.
            return runCatching { json.decodeFromString<List<EnvironmentRecord>>(encoded) }
                .getOrNull()
                .orEmpty()
        }
        return this[LEGACY_ENVIRONMENT_KEY]
            ?.let { encoded ->
                runCatching { json.decodeFromString<EnvironmentRecord>(encoded) }.getOrNull()
            }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeCatalog(
        records: List<EnvironmentRecord>,
    ) {
        this[CATALOG_KEY] = json.encodeToString(records)
        remove(LEGACY_ENVIRONMENT_KEY)
    }

    private companion object {
        val CATALOG_KEY = stringPreferencesKey("environment_catalog")

        /** Written by builds that paired with exactly one desktop. */
        val LEGACY_ENVIRONMENT_KEY = stringPreferencesKey("paired_environment")
    }
}
