package com.silverbullet.kode.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Supplies the platform HTTP engine: OkHttp on Android, Darwin on iOS.
 * Everything above this line is shared.
 */
expect fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Closes every idle pooled keep-alive connection of the platform engine.
 *
 * A process that returns from a long background suspension may hold pooled
 * connections whose far end silently died while the app was frozen — the
 * kernel never saw a FIN, so reusing one stalls the next request until its
 * timeout instead of failing fast. Evicting the pool makes a resume behave
 * like a cold launch, which always starts on fresh sockets. Only *idle*
 * connections are closed; in-flight requests and live WebSockets are
 * untouched, so calling this is always safe.
 */
expect fun evictIdleHttpConnections()

/** JSON policy for T3 Code contract payloads. */
val ContractJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

fun createKodeHttpClient(): HttpClient = createPlatformHttpClient {
    install(ContentNegotiation) {
        json(ContractJson)
    }
    install(WebSockets)
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
    }
}

private const val REQUEST_TIMEOUT_MS = 15_000L
private const val CONNECT_TIMEOUT_MS = 15_000L
