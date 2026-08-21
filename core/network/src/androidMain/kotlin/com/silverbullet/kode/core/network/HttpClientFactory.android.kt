package com.silverbullet.kode.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * Handed to Ktor as the preconfigured engine so the app owns the connection
 * pool. Ktor derives per-timeout clients from it with `newBuilder()`, which
 * shares this pool — evicting it therefore reaches every derived client.
 */
private val sharedOkHttpClient: OkHttpClient by lazy { OkHttpClient() }

actual fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine { preconfigured = sharedOkHttpClient }
        config()
    }

actual fun evictIdleHttpConnections() {
    sharedOkHttpClient.connectionPool.evictAll()
}
