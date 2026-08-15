package com.silverbullet.kode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ClientOrchestrationCommand` is discriminated by `type`, not by Kotlin's
 * default `type`-named-but-differently-shaped polymorphism, so this pins the
 * encoding against `ClientThreadTurnStartCommand` in the contracts.
 */
class OrchestrationCommandTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val command: ClientOrchestrationCommand = ThreadTurnStartCommand(
        commandId = "cmd-1",
        threadId = ThreadId("t1"),
        message = UserMessageInput(messageId = "msg-1", text = "run the tests"),
        runtimeMode = RuntimeMode.AUTO,
        interactionMode = InteractionMode.DEFAULT,
        createdAt = "2026-08-15T10:00:00.000Z",
    )

    @Test
    fun `encodes with the contract's type discriminator`() {
        val encoded = json.encodeToJsonElement(
            ClientOrchestrationCommand.serializer(),
            command,
        ).jsonObject

        assertEquals("thread.turn.start", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("cmd-1", encoded["commandId"]?.jsonPrimitive?.content)
        assertEquals("t1", encoded["threadId"]?.jsonPrimitive?.content)
        assertEquals("auto", encoded["runtimeMode"]?.jsonPrimitive?.content)
        assertEquals("default", encoded["interactionMode"]?.jsonPrimitive?.content)
        assertEquals("2026-08-15T10:00:00.000Z", encoded["createdAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes the nested user message with the required role and attachments`() {
        val message = json.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command)
            .jsonObject["message"]!!.jsonObject

        assertEquals("msg-1", message["messageId"]?.jsonPrimitive?.content)
        assertEquals("run the tests", message["text"]?.jsonPrimitive?.content)
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        // `attachments` is required on the wire even when empty.
        assertTrue(message.containsKey("attachments"))
    }

    @Test
    fun `omits an absent model selection rather than sending null`() {
        // The field is `Schema.optional`, so a null would fail decoding
        // server-side where an absent key succeeds.
        val encoded = json.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command)
            .jsonObject

        assertTrue("modelSelection" !in encoded)
    }

    @Test
    fun `decodes a dispatch result`() {
        assertEquals(7, json.decodeFromString<DispatchResult>("""{"sequence":7}""").sequence)
    }
}
