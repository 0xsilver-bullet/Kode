package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.SessionStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What a thread row says about its state.
 *
 * Port of `resolveThreadListV2Status` in
 * `apps/mobile/src/features/threads/threadListV2.ts`. Four labelled states and
 * one unlabelled resting state: colour is spent on "act now", "in motion" and
 * "broken", so [Ready] shows the thread's age instead of a label that would say
 * nothing.
 */
enum class ThreadRowStatus { Approval, Input, Working, Failed, Ready }

/**
 * The order matters. A thread blocked on the user outranks one merely running,
 * and a broken session outranks live background work — an error the user has
 * not seen is worth more than the news that something is still moving.
 *
 * The last [ThreadRowStatus.Working] branch is a deliberate departure from the
 * port: T3 Code's resolver reads only the session, so a thread whose background
 * work outlived its turn reads as quiet. [OrchestrationThreadShell.isBusy] is
 * this app's own, broader reading of "in motion" — it also counts a running
 * turn and `backgroundLiveness` — and it is what the row indicated before.
 */
fun OrchestrationThreadShell.rowStatus(): ThreadRowStatus = when {
    hasPendingApprovals -> ThreadRowStatus.Approval
    hasPendingUserInput -> ThreadRowStatus.Input
    session?.status == SessionStatus.RUNNING ||
        session?.status == SessionStatus.STARTING -> ThreadRowStatus.Working

    session?.status == SessionStatus.ERROR -> ThreadRowStatus.Failed
    isBusy -> ThreadRowStatus.Working
    else -> ThreadRowStatus.Ready
}

/**
 * How long ago, at the coarsest useful granularity.
 *
 * Port of `relativeTime` in `apps/mobile/src/lib/time.ts`, including its
 * refusal to count seconds: a live seconds ticker changed width every second
 * and reflowed the row around it. An unparseable or absent timestamp reads as
 * `<1m` rather than blank, which is what the port does — a row with no legible
 * age is a row whose age is not worth a different answer.
 */
@OptIn(ExperimentalTime::class)
fun relativeTimeLabel(timestamp: String?, nowMillis: Long): String {
    val instant = timestamp?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return "<1m"

    // Clamped at zero: a timestamp from a device whose clock runs ahead of this
    // one must not render as a negative age.
    val seconds = ((nowMillis - instant.toEpochMilliseconds()) / 1_000L).coerceAtLeast(0L)
    if (seconds < 60L) return "<1m"

    val minutes = seconds / 60L
    if (minutes < 60L) return minutes.toString() + "m"

    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + "h"

    return (hours / 24L).toString() + "d"
}

/**
 * The timestamp a row dates itself by.
 *
 * Prefers the latest user message for the same reason the list sorts by it: it
 * is when the user last touched the thread, which is what "13h" is being asked
 * about.
 */
fun OrchestrationThreadShell.rowTimestamp(): String =
    latestUserMessageAt ?: updatedAt
