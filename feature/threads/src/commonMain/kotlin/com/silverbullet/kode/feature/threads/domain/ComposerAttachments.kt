package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.common.PickedImage
import com.silverbullet.kode.core.model.AttachmentLimits
import com.silverbullet.kode.core.model.AttachmentType
import com.silverbullet.kode.core.model.UploadChatImageAttachment

/**
 * One image staged in a composer, before the turn that carries it is sent.
 *
 * It holds both representations at once and for different consumers:
 * [dataUrl] is what goes on the wire, [previewUri] is what the thumbnail strip
 * renders. They are kept apart on purpose — feeding a multi-megabyte base64
 * string to the image loader would decode it again on every recomposition.
 *
 * [id] is client-side only. The server mints its own when it persists the
 * attachment; this one exists so the strip has a stable key and the remove
 * button has something to name.
 */
@Immutable
data class DraftAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataUrl: String,
    val previewUri: String,
) {
    fun toUpload(): UploadChatImageAttachment = UploadChatImageAttachment(
        type = AttachmentType.IMAGE,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dataUrl = dataUrl,
    )
}

fun List<DraftAttachment>.toUploads(): List<UploadChatImageAttachment> = map { it.toUpload() }

/** Slots left before the server's per-message cap; never negative. */
fun List<DraftAttachment>.remainingAttachmentSlots(): Int =
    (AttachmentLimits.MAX_ATTACHMENTS - size).coerceAtLeast(0)

/**
 * Appends newly picked images, truncating at the per-message cap.
 *
 * Truncation is belt-and-braces: the picker is already told how many slots are
 * free, but the cap is a server-side rejection of the *whole turn*, so it is
 * enforced on both sides of the call rather than trusted to one.
 */
fun List<DraftAttachment>.appendPicked(
    picked: List<PickedImage>,
    newId: () -> String,
): List<DraftAttachment> {
    val room = remainingAttachmentSlots()
    if (room == 0 || picked.isEmpty()) return this

    return this + picked.take(room).map { image ->
        DraftAttachment(
            id = newId(),
            name = image.name,
            mimeType = image.mimeType,
            sizeBytes = image.sizeBytes,
            dataUrl = image.dataUrl,
            previewUri = image.previewUri,
        )
    }
}

fun List<DraftAttachment>.removeAttachment(id: String): List<DraftAttachment> =
    filterNot { it.id == id }

/**
 * The message shown when the user tries to attach with no slots left, or null
 * when there is room.
 */
fun List<DraftAttachment>.attachmentCapReason(): String? =
    if (remainingAttachmentSlots() > 0) {
        null
    } else {
        "You can attach up to ${AttachmentLimits.MAX_ATTACHMENTS} images per message."
    }
