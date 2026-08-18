package com.silverbullet.kode.core.datastore.di

import com.silverbullet.kode.core.datastore.DataStoreEnvironmentCatalogStore
import com.silverbullet.kode.core.datastore.DataStoreVoiceBindingStore
import com.silverbullet.kode.core.datastore.DataStoreVoiceSettingsStore
import com.silverbullet.kode.core.datastore.EnvironmentCatalogStore
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.core.datastore.VoiceSettingsStore
import com.silverbullet.kode.core.datastore.createKodeDataStore
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Qualifier for the absolute path of the preferences file. Each platform's DI
 * module supplies it, which is why the store itself needs no `expect`/`actual`.
 */
val DataStorePathQualifier = named("kode.datastore.path")

val dataStoreModule = module {
    single { createKodeDataStore { get(DataStorePathQualifier) } }
    single<EnvironmentCatalogStore> { DataStoreEnvironmentCatalogStore(dataStore = get()) }
    single<VoiceBindingStore> { DataStoreVoiceBindingStore(dataStore = get()) }
    single<VoiceSettingsStore> { DataStoreVoiceSettingsStore(dataStore = get()) }
}
