package com.silverbullet.kode.core.datastore.di

import com.silverbullet.kode.core.datastore.DataStoreEnvironmentStore
import com.silverbullet.kode.core.datastore.EnvironmentStore
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
    single<EnvironmentStore> { DataStoreEnvironmentStore(dataStore = get()) }
}
