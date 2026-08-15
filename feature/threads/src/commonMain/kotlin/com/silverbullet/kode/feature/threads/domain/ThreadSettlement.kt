package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.SessionStatus
import kotlin.math.abs
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration
import kotlin.time.Instant

/**
 * Whether a thread counts as finished business.
 *
 * Ported from `effectiveSettled` in
 * `packages/client-runtime/src/state/threadSettled.ts`. Order matters: the
 * activity blockers come first so a thread that still needs the user is never
 * filed away, even if it was explicitly settled earlier.
 *
 * @param now ISO-8601 timestamp to judge staleness against.
 * @param autoSettleAfterDays how long a quiet thread stays active. Null
 *  disables age-based settling entirely, leaving only explicit pins.
 */
@OptIn(ExperimentalTime::class)
fun OrchestrationThreadShell.isEffectivelySettled(
    now: String,
    autoSettleAfterDays: Int? = DEFAULT_AUTO_SETTLE_AFTER_DAYS,
): Boolean {
    // Blocked work stays visible even when the user explicitly settled it —
    // burying a thread that is waiting on an answer would strand the turn.
    if (hasPendingApprovals || hasPendingUserInput) return false
    if (session?.status == SessionStatus.STARTING || session?.status == SessionStatus.RUNNING) {
        return false
    }

    if (hasQueuedTurnStart(now)) {
        // One forgivable exception: the queued-turn check is clock-derived, and
        // the list passes a coarser `now` than the settle action did. If the
        // server already accepted a settle *after* the message, trust its
        // ruling rather than pinning the row active until our clock catches up.
        val acceptedAt = settledAt
        val messagedAt = latestUserMessageAt
        val serverAdjudicated = settledOverride == SettledOverride.SETTLED &&
            acceptedAt != null &&
            messagedAt != null &&
            acceptedAt.isAtOrAfter(messagedAt)
        if (!serverAdjudicated) return false
    }

    if (settledOverride == SettledOverride.SETTLED) return true
    // The explicit keep-active pin suppresses auto-settle until real activity
    // clears it server-side.
    if (settledOverride == SettledOverride.ACTIVE) return false

    if (autoSettleAfterDays == null) return false
    val lastActivity = lastActivityAt() ?: return false

    val last = lastActivity.parseInstantOrNull() ?: return false
    val nowInstant = now.parseInstantOrNull() ?: return false
    // A malformed timestamp must never produce a surprise auto-settle, so every
    // parse failure above leaves the thread active.
    return last < nowInstant - autoSettleAfterDays.toLong().toDuration(DurationUnit.DAYS)
}

/** `settledOverride` values. */
object SettledOverride {
    const val SETTLED = "settled"
    const val ACTIVE = "active"
}

/** The mobile app's default, from `threadListV2.ts`. */
const val DEFAULT_AUTO_SETTLE_AFTER_DAYS: Int = 3

/**
 * Latest of anything that counts as activity.
 *
 * `updatedAt` is deliberately not a candidate: it moves for bookkeeping the
 * user never sees, which would keep dead threads out of the shelf forever.
 */
fun OrchestrationThreadShell.lastActivityAt(): String? = listOfNotNull(
    latestUserMessageAt,
    latestTurn?.requestedAt,
    latestTurn?.startedAt,
    latestTurn?.completedAt,
).maxByOrNull { it }

/**
 * A message was sent but no turn has picked it up yet.
 *
 * Without this, a thread you just messaged from the phone could drop into the
 * shelf during the seconds before its turn starts.
 */
@OptIn(ExperimentalTime::class)
internal fun OrchestrationThreadShell.hasQueuedTurnStart(now: String): Boolean {
    val messageAt = latestUserMessageAt?.parseInstantOrNull() ?: return false
    // A failed start clears the queued state; the failure is already visible.
    if (session?.status == SessionStatus.ERROR) return false

    val nowInstant = now.parseInstantOrNull() ?: return false
    // Bounded on both sides: message timestamps come from whichever device sent
    // them, so a clock running ahead of this one would otherwise hold the
    // queued state for the whole skew.
    if (abs((nowInstant - messageAt).inWholeMilliseconds) > QUEUED_TURN_START_GRACE_MILLIS) {
        return false
    }

    val turn = latestTurn ?: return true
    // Still queued unless some turn timestamp is at or after the message.
    return listOfNotNull(turn.requestedAt, turn.startedAt, turn.completedAt)
        .none { it.parseInstantOrNull()?.let { parsed -> parsed >= messageAt } == true }
}

private const val QUEUED_TURN_START_GRACE_MILLIS = 2L * 60L * 1_000L

/**
 * Splits the list into what still wants attention and what is finished.
 *
 * Order within each half is preserved, so the caller's sort still governs.
 */
fun List<OrchestrationThreadShell>.partitionBySettlement(
    now: String,
    autoSettleAfterDays: Int? = DEFAULT_AUTO_SETTLE_AFTER_DAYS,
): ThreadPartition {
    val active = ArrayList<OrchestrationThreadShell>(size)
    val settled = ArrayList<OrchestrationThreadShell>()
    for (thread in this) {
        if (thread.isEffectivelySettled(now, autoSettleAfterDays)) settled += thread else active += thread
    }
    return ThreadPartition(active = active, settled = settled)
}

data class ThreadPartition(
    val active: List<OrchestrationThreadShell>,
    val settled: List<OrchestrationThreadShell>,
)

@OptIn(ExperimentalTime::class)
private fun String.parseInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

@OptIn(ExperimentalTime::class)
private fun String.isAtOrAfter(other: String): Boolean {
    val a = parseInstantOrNull() ?: return false
    val b = other.parseInstantOrNull() ?: return false
    return a >= b
}

