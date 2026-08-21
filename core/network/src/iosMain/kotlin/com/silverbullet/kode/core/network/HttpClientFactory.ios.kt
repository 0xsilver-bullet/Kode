package com.silverbullet.kode.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin, config)

actual fun evictIdleHttpConnections() {
    // NSURLSession validates pooled connections itself and the iOS host has no
    // lifecycle monitor yet, so there is nothing to evict here.
}
