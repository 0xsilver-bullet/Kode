package com.silverbullet.kode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat attachments, mirroring `ChatAttachment` / `UploadChatAttachment` in
 * `packages/contracts/src/orchestration.ts`.
 *
 * The two shapes are deliberately different, and the asymmetry is the whole
 * protocol: the client *uploads* bytes inline as a data URL and gets back a
 * server-assigned id, which is what every later reference uses. There is no
 * separate upload call — the bytes ride on `thread.turn.start` itself.
 */

/**
 * An attachment as it exists on a message: an id, never the bytes.
 *
 * `ChatAttachment` is a union in the contract, but `image` is its only member
 * today, so [type] is carried as a plain string rather than modelled as a
 * sealed hierarchy that would have exactly one case.
 */
@Serializable
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val type: String = AttachmentType.IMAGE,
)

/**
 * An attachment as it is sent: the bytes inline, and no id — the server mints
 * one when it persists the attachment and echoes it back as a [ChatAttachment].
 *
 * [dataUrl] is a full `data:<mime>;base64,<payload>` URL, not bare base64.
 */
@Serializable
data class UploadChatImageAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataUrl: String,
    val type: String = AttachmentType.IMAGE,
)

object AttachmentType {
    const val IMAGE = "image"
}

/**
 * The server's send limits, from `PROVIDER_SEND_TURN_*` in the contract.
 *
 * Enforced client-side because the server rejects the *whole turn* when a
 * payload violates them — losing the text along with the offending image. The
 * numbers are duplicated here rather than discovered at runtime because the
 * contract makes them constants, not capabilities.
 */
object AttachmentLimits {
    const val MAX_ATTACHMENTS = 8
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    /**
     * SVG is deliberately absent: the contract excludes it because providers
     * cannot read vector input, and it is an XML parsing surface besides.
     */
    val SUPPORTED_IMAGE_MIME_TYPES = setOf(
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    fun isSupportedImageMimeType(mimeType: String): Boolean =
        mimeType.lowercase() in SUPPORTED_IMAGE_MIME_TYPES
}

/**
 * `AssetResource` — what a signed asset URL is being requested *for*.
 *
 * The contract declares a union tagged with `_tag` over workspace files,
 * attachments and project favicons. Only the attachment case is modelled: it is
 * the only one this client asks for, and following the union with a sealed
 * hierarchy would need a second [kotlinx.serialization.json.Json] instance,
 * because `ContractJson` discriminates on `type` for orchestration commands.
 */
@Serializable
data class AssetResource(
    val attachmentId: String,
    @SerialName("_tag") val tag: String = AssetResourceTag.ATTACHMENT,
)

object AssetResourceTag {
    const val ATTACHMENT = "attachment"
}

@Serializable
data class AssetCreateUrlInput(val resource: AssetResource)

/**
 * A signed, *unauthenticated* URL relative to the environment's HTTP base.
 *
 * The signature is the authorization: the server serves `/api/assets/<token>/…`
 * without a bearer header, so an image loader needs no credentials. [expiresAt]
 * is epoch milliseconds — the server issues a one-hour token.
 */
@Serializable
data class AssetCreateUrlResult(
    val relativeUrl: String,
    val expiresAt: Long,
    val sourcePath: String? = null,
)
