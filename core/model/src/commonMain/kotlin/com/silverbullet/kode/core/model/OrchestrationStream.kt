package com.silverbullet.kode.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Items emitted by `orchestration.subscribeShell`.
 *
 * Decoded by hand rather than through a sealed serializer, for the same reason
 * as the RPC frames: a `kind` this build has never seen must degrade to
 * [Unsupported] and be skipped, not fail the subscription.
 */
sealed interface ShellStreamItem {
    /** Catch-up finished; everything after this is live. */
    data object Synchronized : ShellStreamItem

    data class Snapshot(val snapshot: OrchestrationShellSnapshot) : ShellStreamItem
    data class ThreadUpserted(val sequence: Int, val thread: OrchestrationThreadShell) :
        ShellStreamItem

    data class ThreadRemoved(val sequence: Int, val threadId: ThreadId) : ShellStreamItem
    data class ProjectUpserted(val sequence: Int, val project: OrchestrationProjectShell) :
        ShellStreamItem

    data class ProjectRemoved(val sequence: Int, val projectId: ProjectId) : ShellStreamItem
    data class Unsupported(val kind: String) : ShellStreamItem
}

/** Items emitted by `orchestration.subscribeThread`. */
sealed interface ThreadStreamItem {
    data object Synchronized : ThreadStreamItem
    data class Snapshot(val snapshot: OrchestrationThreadDetailSnapshot) : ThreadStreamItem
    data class Event(val event: OrchestrationEvent) : ThreadStreamItem
    data class Unsupported(val kind: String) : ThreadStreamItem
}

/**
 * The subset of `OrchestrationEvent` this build understands.
 *
 * The contract declares 29 variants. Handling the four below is enough for a
 * live timeline; the rest arrive as [Unsupported] and are ignored. See
 * `ROADMAP.md` for what that costs and how it is bounded.
 */
sealed interface OrchestrationEvent {
    val sequence: Int

    /**
     * Covers user messages *and* streaming assistant output: the server turns
     * each `thread.message.assistant.delta` into one of these carrying the full
     * accumulated text, so the client upserts by message id.
     */
    data class MessageSent(
        override val sequence: Int,
        val threadId: ThreadId,
        val message: OrchestrationMessage,
    ) : OrchestrationEvent

    data class ActivityAppended(
        override val sequence: Int,
        val threadId: ThreadId,
        val activity: OrchestrationThreadActivity,
    ) : OrchestrationEvent

    data class SessionSet(
        override val sequence: Int,
        val threadId: ThreadId,
        val session: OrchestrationSession,
    ) : OrchestrationEvent

    data class TurnDiffCompleted(
        override val sequence: Int,
        val threadId: ThreadId,
    ) : OrchestrationEvent

    /**
     * The thread's metadata changed — title, or the model it runs on.
     *
     * Ignoring this was why a model change never appeared to take: the command
     * was accepted, but nothing updated the thread we render from.
     */
    data class MetaUpdated(
        override val sequence: Int,
        val threadId: ThreadId,
        val modelSelection: ModelSelection?,
        val title: String?,
    ) : OrchestrationEvent

    data class RuntimeModeSet(
        override val sequence: Int,
        val threadId: ThreadId,
        val runtimeMode: String,
    ) : OrchestrationEvent

    data class InteractionModeSet(
        override val sequence: Int,
        val threadId: ThreadId,
        val interactionMode: String,
    ) : OrchestrationEvent

    data class Unsupported(
        override val sequence: Int,
        val type: String,
    ) : OrchestrationEvent
}

// ------------------------------------------------------------------- payloads

@Serializable
private data class ThreadMessageSentPayload(
    val threadId: ThreadId,
    val messageId: String,
    val role: String,
    val text: String = "",
    val turnId: String? = null,
    val streaming: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
) {
    fun toMessage() = OrchestrationMessage(
        id = messageId,
        role = role,
        text = text,
        turnId = turnId,
        streaming = streaming,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class ThreadActivityAppendedPayload(
    val threadId: ThreadId,
    val activity: OrchestrationThreadActivity,
)

@Serializable
private data class ThreadSessionSetPayload(
    val threadId: ThreadId,
    val session: OrchestrationSession,
)

@Serializable
private data class ThreadIdPayload(val threadId: ThreadId)

@Serializable
private data class ThreadMetaUpdatedPayload(
    val threadId: ThreadId,
    val modelSelection: ModelSelection? = null,
    val title: String? = null,
)

@Serializable
private data class ThreadRuntimeModeSetPayload(
    val threadId: ThreadId,
    val runtimeMode: String,
)

@Serializable
private data class ThreadInteractionModeSetPayload(
    val threadId: ThreadId,
    val interactionMode: String = InteractionMode.DEFAULT,
)

// ------------------------------------------------------------------- decoding

/**
 * Decodes orchestration stream items.
 *
 * A malformed *known* item still throws — that is a real bug worth surfacing.
 * Only genuinely unrecognised discriminators are tolerated.
 */
class OrchestrationStreamDecoder(private val json: Json) {

    fun decodeShellItem(element: JsonElement): ShellStreamItem {
        val obj = element.asObject()
        return when (val kind = obj.string("kind")) {
            "synchronized" -> ShellStreamItem.Synchronized
            "snapshot" -> ShellStreamItem.Snapshot(obj.decode("snapshot"))
            "thread-upserted" -> ShellStreamItem.ThreadUpserted(
                sequence = obj.int("sequence"),
                thread = obj.decode("thread"),
            )

            "thread-removed" -> ShellStreamItem.ThreadRemoved(
                sequence = obj.int("sequence"),
                threadId = ThreadId(obj.requireString("threadId")),
            )

            "project-upserted" -> ShellStreamItem.ProjectUpserted(
                sequence = obj.int("sequence"),
                project = obj.decode("project"),
            )

            "project-removed" -> ShellStreamItem.ProjectRemoved(
                sequence = obj.int("sequence"),
                projectId = ProjectId(obj.requireString("projectId")),
            )

            else -> ShellStreamItem.Unsupported(kind ?: "<missing>")
        }
    }

    fun decodeThreadItem(element: JsonElement): ThreadStreamItem {
        val obj = element.asObject()
        return when (val kind = obj.string("kind")) {
            "synchronized" -> ThreadStreamItem.Synchronized
            "snapshot" -> ThreadStreamItem.Snapshot(obj.decode("snapshot"))
            "event" -> ThreadStreamItem.Event(
                decodeEvent(obj["event"] ?: throw OrchestrationDecodeException("event")),
            )

            else -> ThreadStreamItem.Unsupported(kind ?: "<missing>")
        }
    }

    private fun decodeEvent(element: JsonElement): OrchestrationEvent {
        val obj = element.asObject()
        val sequence = obj.int("sequence")
        val type = obj.string("type") ?: "<missing>"
        val payload = obj["payload"]

        return when (type) {
            "thread.message-sent" -> {
                val decoded: ThreadMessageSentPayload = decodePayload(payload, type)
                OrchestrationEvent.MessageSent(
                    sequence = sequence,
                    threadId = decoded.threadId,
                    message = decoded.toMessage(),
                )
            }

            "thread.activity-appended" -> {
                val decoded: ThreadActivityAppendedPayload = decodePayload(payload, type)
                OrchestrationEvent.ActivityAppended(sequence, decoded.threadId, decoded.activity)
            }

            "thread.session-set" -> {
                val decoded: ThreadSessionSetPayload = decodePayload(payload, type)
                OrchestrationEvent.SessionSet(sequence, decoded.threadId, decoded.session)
            }

            "thread.meta-updated" -> {
                val decoded: ThreadMetaUpdatedPayload = decodePayload(payload, type)
                OrchestrationEvent.MetaUpdated(
                    sequence = sequence,
                    threadId = decoded.threadId,
                    modelSelection = decoded.modelSelection,
                    title = decoded.title,
                )
            }

            "thread.runtime-mode-set" -> {
                val decoded: ThreadRuntimeModeSetPayload = decodePayload(payload, type)
                OrchestrationEvent.RuntimeModeSet(sequence, decoded.threadId, decoded.runtimeMode)
            }

            "thread.interaction-mode-set" -> {
                val decoded: ThreadInteractionModeSetPayload = decodePayload(payload, type)
                OrchestrationEvent.InteractionModeSet(
                    sequence,
                    decoded.threadId,
                    decoded.interactionMode,
                )
            }

            "thread.turn-diff-completed" -> {
                val decoded: ThreadIdPayload = decodePayload(payload, type)
                OrchestrationEvent.TurnDiffCompleted(sequence, decoded.threadId)
            }

            else -> OrchestrationEvent.Unsupported(sequence, type)
        }
    }

    private inline fun <reified T> decodePayload(payload: JsonElement?, type: String): T {
        val element = payload ?: throw OrchestrationDecodeException("$type payload")
        return runCatching { json.decodeFromJsonElement(serializer<T>(), element) }
            .getOrElse { throw OrchestrationDecodeException("$type payload", it) }
    }

    private inline fun <reified T> JsonObject.decode(key: String): T {
        val element = this[key] ?: throw OrchestrationDecodeException(key)
        return runCatching { json.decodeFromJsonElement(serializer<T>(), element) }
            .getOrElse { throw OrchestrationDecodeException(key, it) }
    }

    private fun JsonElement?.asObject(): JsonObject =
        this as? JsonObject ?: throw OrchestrationDecodeException("stream item")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.requireString(key: String): String =
        string(key) ?: throw OrchestrationDecodeException(key)

    /** Sequence is absent on snapshot-like items; 0 is a safe floor. */
    private fun JsonObject.int(key: String): Int = string(key)?.toIntOrNull() ?: 0
}

class OrchestrationDecodeException(
    what: String,
    cause: Throwable? = null,
) : Exception("Could not decode `$what`.", cause)
