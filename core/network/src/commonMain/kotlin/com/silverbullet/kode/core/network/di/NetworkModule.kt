package com.silverbullet.kode.core.network.di

import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.WebSocketRpcTransport
import com.silverbullet.kode.core.network.createKodeHttpClient
import org.koin.dsl.module

/**
 * One HTTP client for the whole app: it owns the connection pool, and the
 * WebSocket plugin is installed once.
 */
val networkModule = module {
    single { createKodeHttpClient() }
    single { EnvironmentAuthApi(httpClient = get()) }
    single { WebSocketRpcTransport(httpClient = get()) }
}
