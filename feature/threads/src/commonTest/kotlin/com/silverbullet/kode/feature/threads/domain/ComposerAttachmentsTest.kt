package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.common.PickedImage
import com.silverbullet.kode.core.model.AttachmentLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerAttachmentsTest {

    private fun picked(name: String) = PickedImage(
        name = name,
        mimeType = "image/png",
        sizeBytes = 1024,
        dataUrl = "data:image/png;base64,AAAA",
        previewUri = "content://media/$name",
    )

    private fun ids(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    private fun staged(count: Int): List<DraftAttachment> =
        emptyList<DraftAttachment>().appendPicked(
            List(count) { picked("image-$it.png") },
            ids(),
        )

    @Test
    fun `picked images become drafts carrying both representations`() {
        val drafts = emptyList<DraftAttachment>().appendPicked(listOf(picked("a.png")), ids())

        val draft = drafts.single()
        assertEquals("a.png", draft.name)
        assertEquals("image/png", draft.mimeType)
        assertEquals(1024L, draft.sizeBytes)
        // The wire form and the thumbnail form are deliberately different
        // values, so the strip never renders megabytes of base64.
        assertEquals("data:image/png;base64,AAAA", draft.dataUrl)
        assertEquals("content://media/a.png", draft.previewUri)
    }

    @Test
    fun `appending truncates at the server's per message cap`() {
        val drafts = staged(AttachmentLimits.MAX_ATTACHMENTS + 4)

        assertEquals(AttachmentLimits.MAX_ATTACHMENTS, drafts.size)
    }

    @Test
    fun `appending to a full list is a no-op rather than a replacement`() {
        val full = staged(AttachmentLimits.MAX_ATTACHMENTS)

        val after = full.appendPicked(listOf(picked("late.png")), ids())

        assertEquals(full, after)
    }

    @Test
    fun `remaining slots never goes negative`() {
        assertEquals(
            AttachmentLimits.MAX_ATTACHMENTS,
            emptyList<DraftAttachment>().remainingAttachmentSlots(),
        )
        assertEquals(0, staged(AttachmentLimits.MAX_ATTACHMENTS).remainingAttachmentSlots())
    }

    @Test
    fun `the cap reason appears only once there is no room`() {
        assertNull(staged(AttachmentLimits.MAX_ATTACHMENTS - 1).attachmentCapReason())

        val reason = staged(AttachmentLimits.MAX_ATTACHMENTS).attachmentCapReason()
        assertNotNull(reason)
        assertTrue(reason.contains(AttachmentLimits.MAX_ATTACHMENTS.toString()))
    }

    @Test
    fun `removing takes out one attachment and leaves the order intact`() {
        val drafts = staged(3)

        val after = drafts.removeAttachment(drafts[1].id)

        assertEquals(listOf(drafts[0].id, drafts[2].id), after.map { it.id })
    }

    @Test
    fun `removing an unknown id changes nothing`() {
        val drafts = staged(2)

        assertEquals(drafts, drafts.removeAttachment("not-staged"))
    }

    @Test
    fun `uploads drop the client side id and preview handle`() {
        val uploads = staged(2).toUploads()

        assertEquals(2, uploads.size)
        uploads.forEach { upload ->
            assertEquals("image", upload.type)
            assertEquals("data:image/png;base64,AAAA", upload.dataUrl)
        }
    }
}
