package com.silverbullet.kode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the two attachment shapes against `orchestration.ts`.
 *
 * The asymmetry is the point: what the client *sends* carries bytes and no id,
 * what it *receives* carries an id and no bytes. Getting that backwards
 * produces a schema rejection of the whole turn, so it is worth a test.
 */
class AttachmentsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `an upload attachment carries the data url and no id`() {
        val encoded = json.encodeToJsonElement(
            UploadChatImageAttachment.serializer(),
            UploadChatImageAttachment(
                name = "screenshot.png",
                mimeType = "image/png",
                sizeBytes = 2048,
                dataUrl = "data:image/png;base64,AAAA",
            ),
        ).jsonObject

        assertEquals("image", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("screenshot.png", encoded["name"]?.jsonPrimitive?.content)
        assertEquals("image/png", encoded["mimeType"]?.jsonPrimitive?.content)
        assertEquals("2048", encoded["sizeBytes"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,AAAA", encoded["dataUrl"]?.jsonPrimitive?.content)
        assertFalse(encoded.containsKey("id"))
    }

    @Test
    fun `a turn start command carries its uploads inline`() {
        val command: ClientOrchestrationCommand = ThreadTurnStartCommand(
            commandId = "cmd-1",
            threadId = ThreadId("t1"),
            message = UserMessageInput(
                messageId = "msg-1",
                text = "what is wrong here?",
                attachments = listOf(
                    UploadChatImageAttachment(
                        name = "a.png",
                        mimeType = "image/png",
                        sizeBytes = 12,
                        dataUrl = "data:image/png;base64,AAAA",
                    ),
                ),
            ),
            runtimeMode = RuntimeMode.AUTO,
            interactionMode = InteractionMode.DEFAULT,
            createdAt = "2026-08-18T10:00:00.000Z",
        )

        val attachments = json
            .encodeToJsonElement(ClientOrchestrationCommand.serializer(), command)
            .jsonObject["message"]!!.jsonObject["attachments"]!!.jsonArray

        assertEquals(1, attachments.size)
        assertEquals(
            "data:image/png;base64,AAAA",
            attachments[0].jsonObject["dataUrl"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a received attachment decodes with its server assigned id`() {
        val decoded = json.decodeFromString(
            ChatAttachment.serializer(),
            """{"type":"image","id":"att_1","name":"a.png","mimeType":"image/png","sizeBytes":12}""",
        )

        assertEquals("att_1", decoded.id)
        assertEquals("image/png", decoded.mimeType)
        assertEquals(12L, decoded.sizeBytes)
    }

    @Test
    fun `a message without attachments decodes to an empty list`() {
        val decoded = json.decodeFromString(
            OrchestrationMessage.serializer(),
            """{"id":"m1","role":"user","text":"hi","createdAt":"t","updatedAt":"t"}""",
        )

        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun `the asset resource uses the contract's underscore tag`() {
        val encoded = json.encodeToJsonElement(
            AssetCreateUrlInput.serializer(),
            AssetCreateUrlInput(AssetResource(attachmentId = "att_1")),
        ).jsonObject["resource"]!!.jsonObject

        assertEquals("attachment", encoded["_tag"]?.jsonPrimitive?.content)
        assertEquals("att_1", encoded["attachmentId"]?.jsonPrimitive?.content)
        // `tag` is the Kotlin property name; it must not leak onto the wire.
        assertFalse(encoded.containsKey("tag"))
    }

    @Test
    fun `the asset url result tolerates a missing source path`() {
        val decoded = json.decodeFromString(
            AssetCreateUrlResult.serializer(),
            """{"relativeUrl":"/api/assets/tok/a.png","expiresAt":1750000000000}""",
        )

        assertEquals("/api/assets/tok/a.png", decoded.relativeUrl)
        assertEquals(1750000000000L, decoded.expiresAt)
        assertNull(decoded.sourcePath)
    }

    @Test
    fun `supported image mime types match the contract and exclude svg`() {
        assertTrue(AttachmentLimits.isSupportedImageMimeType("image/png"))
        assertTrue(AttachmentLimits.isSupportedImageMimeType("IMAGE/JPEG"))
        assertTrue(AttachmentLimits.isSupportedImageMimeType("image/webp"))
        assertTrue(AttachmentLimits.isSupportedImageMimeType("image/gif"))
        assertFalse(AttachmentLimits.isSupportedImageMimeType("image/svg+xml"))
        assertFalse(AttachmentLimits.isSupportedImageMimeType("image/heic"))
    }
}
