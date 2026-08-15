package com.silverbullet.kode.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

/**
 * Creates the shared preferences store.
 *
 * There is no `expect`/`actual` here on purpose: only the *path* is
 * platform-specific, and each platform's DI module supplies it. That keeps the
 * store construction itself in `commonMain`.
 */
fun createKodeDataStore(producePath: () -> String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { producePath().toPath() })

const val KODE_DATASTORE_FILE_NAME: String = "kode.preferences_pb"
