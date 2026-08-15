package com.silverbullet.kode.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native has no public `Dispatchers.IO`. `Dispatchers.Default` is the
 * right substitute here: everything we offload is non-blocking I/O on the
 * Darwin engine, so there is no thread to starve.
 */
internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default
