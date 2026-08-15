package com.silverbullet.kode.core.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Branded identifiers mirroring `packages/contracts/src/baseSchemas.ts`.
 *
 * T3 Code brands these on the TypeScript side; value classes give us the same
 * compile-time separation with no runtime allocation.
 */
@JvmInline
@Serializable
value class EnvironmentId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ProjectId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ThreadId(val value: String) {
    override fun toString(): String = value
}
