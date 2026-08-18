package com.silverbullet.kode.core.common

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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

    /**
     * The same instant as epoch milliseconds, for expiry arithmetic on
     * server-issued deadlines.
     *
     * Derived from [nowIso] rather than declared alongside it so the interface
     * stays a SAM — a test fake pins one clock and both readings follow it.
     */
    @OptIn(ExperimentalTime::class)
    fun nowMillis(): Long = Instant.parse(nowIso()).toEpochMilliseconds()
}

@OptIn(ExperimentalUuidApi::class)
class UuidIdGenerator : IdGenerator {
    override fun newId(): String = Uuid.random().toString()
}

@OptIn(ExperimentalTime::class)
class SystemTimeProvider : TimeProvider {
    override fun nowIso(): String = Clock.System.now().toString()
}
