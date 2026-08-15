package com.silverbullet.kode.core.common

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client-generated identifiers for commands and messages.
 *
 * Injected rather than called inline so dispatched commands are deterministic
 * under test. T3 Code brands these as `TrimmedNonEmptyString`, so any non-empty
 * string is valid on the wire.
 */
fun interface IdGenerator {
    fun newId(): String
}

/** Wall-clock time as an ISO-8601 string, which is what `IsoDateTime` expects. */
fun interface TimeProvider {
    fun nowIso(): String
}

@OptIn(ExperimentalUuidApi::class)
class UuidIdGenerator : IdGenerator {
    override fun newId(): String = Uuid.random().toString()
}

@OptIn(ExperimentalTime::class)
class SystemTimeProvider : TimeProvider {
    override fun nowIso(): String = Clock.System.now().toString()
}
