package com.silverbullet.kode.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over the platform dispatchers so shared code never touches
 * [Dispatchers] directly. Tests substitute a deterministic implementation.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

/**
 * `Dispatchers.IO` only exists on the JVM/Native targets, so the IO dispatcher
 * is resolved per platform.
 */
internal expect val platformIoDispatcher: CoroutineDispatcher

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = platformIoDispatcher
}
