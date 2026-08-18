package com.silverbullet.kode.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.silverbullet.kode.core.common.ImagePickOutcome
import com.silverbullet.kode.core.common.ImagePicker
import com.silverbullet.kode.core.common.PickedImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery picking on Android, via the system photo picker.
 *
 * `PickMultipleVisualMedia` is deliberate: it needs **no runtime permission**
 * because the user grants access to exactly the images they select, inside a
 * system UI the app never sees. Asking for `READ_MEDIA_IMAGES` to do the same
 * job would trade a silent, scoped grant for a scary all-photos prompt.
 *
 * Like [AndroidMicPermission], the launcher must be registered before the
 * activity starts, so [attach] is called from `MainActivity.onCreate` and again
 * after every configuration change; the singleton keeps the newest launcher.
 */
class AndroidImagePicker(private val context: Context) : ImagePicker {

    override val isAvailable: Boolean = true

    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pending: CompletableDeferred<List<Uri>>? = null

    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTABLE),
        ) { uris ->
            pending?.complete(uris)
            pending = null
        }
    }

    override suspend fun pick(
        maxCount: Int,
        maxBytes: Long,
        supportedMimeTypes: Set<String>,
    ): ImagePickOutcome {
        if (maxCount <= 0) return ImagePickOutcome.Picked(emptyList())
        val launcher = launcher
            ?: return ImagePickOutcome.Failed("The photo picker is not ready yet.")

        // One pick at a time; a concurrent caller awaits the same result rather
        // than launching a second picker over the first.
        val uris = pending?.await() ?: run {
            val deferred = CompletableDeferred<List<Uri>>()
            pending = deferred
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            deferred.await()
        }

        if (uris.isEmpty()) return ImagePickOutcome.Cancelled

        // The system picker's selection limit is fixed when the launcher is
        // registered, so it cannot be narrowed to the slots actually left. Any
        // overflow is dropped here — and said out loud, because a strip shorter
        // than what the user selected otherwise reads as a bug.
        val overflow = (uris.size - maxCount).coerceAtLeast(0)

        // Decoding and re-encoding is CPU- and allocation-heavy; it must not run
        // on the frame the picker returns on.
        return withContext(Dispatchers.Default) {
            val images = mutableListOf<PickedImage>()
            var failed = 0

            for (uri in uris.take(maxCount)) {
                val encoded = runCatching { encode(uri, maxBytes, supportedMimeTypes) }
                    .getOrNull()
                if (encoded == null) failed++ else images += encoded
            }

            ImagePickOutcome.Picked(
                images = images,
                warning = warningFor(failed = failed, overflow = overflow, room = maxCount),
            )
        }
    }

    /**
     * The two partial failures, in one line.
     *
     * Phrased in terms of the room that was left rather than the per-message
     * cap: the caller owns that policy, and quoting a number this class does not
     * decide would drift the moment the contract changes.
     */
    private fun warningFor(failed: Int, overflow: Int, room: Int): String? = buildList {
        when (failed) {
            0 -> Unit
            1 -> add("One image could not be attached.")
            else -> add("$failed images could not be attached.")
        }
        if (overflow > 0) {
            add(
                if (room == 1) {
                    "Only one more image fit in this message."
                } else {
                    "Only $room more images fit in this message."
                },
            )
        }
    }.joinToString(" ").ifEmpty { null }

    /**
     * Reads one image and produces a wire-ready data URL, shrinking it if it
     * does not fit.
     *
     * The strategy is two-stage, because the two ways an image is "too big" are
     * different problems. Dimensions are handled first, by `inSampleSize`, so a
     * 50-megapixel photo is never fully decoded into memory — that is an OOM,
     * not a size-limit failure. Byte count is handled second, by re-encoding as
     * JPEG at falling quality, because a modest-resolution photo can still
     * exceed the limit through sheer detail.
     *
     * Anything already under the limit in a format the server accepts is passed
     * through byte-for-byte: re-encoding a PNG screenshot as JPEG would blur
     * exactly the text the agent is being asked to read.
     */
    private fun encode(
        uri: Uri,
        maxBytes: Long,
        supportedMimeTypes: Set<String>,
    ): PickedImage? {
        val declaredMimeType = context.contentResolver.getType(uri)?.lowercase()
        val name = displayName(uri) ?: "image"

        val original = readBytes(uri) ?: return null
        if (
            original.size <= maxBytes &&
            declaredMimeType != null &&
            declaredMimeType in supportedMimeTypes
        ) {
            return PickedImage(
                name = name,
                mimeType = declaredMimeType,
                sizeBytes = original.size.toLong(),
                dataUrl = dataUrl(declaredMimeType, original),
                previewUri = uri.toString(),
            )
        }

        // Either too large or a format the server rejects (HEIC being the common
        // case on iPhone-sourced libraries) — both are fixed by re-encoding.
        val bitmap = decodeBounded(original) ?: return null
        // Recycled on every path, including the give-up one: these bitmaps are
        // tens of megabytes, and eight of them leaked is an OOM on the next pick.
        val jpeg = try {
            compressToFit(bitmap, maxBytes)
        } finally {
            bitmap.recycle()
        } ?: return null

        return PickedImage(
            name = renameToJpeg(name),
            mimeType = JPEG_MIME_TYPE,
            sizeBytes = jpeg.size.toLong(),
            dataUrl = dataUrl(JPEG_MIME_TYPE, jpeg),
            previewUri = uri.toString(),
        )
    }

    private fun readBytes(uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    private fun displayName(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }

    /**
     * Decodes with the long edge capped, in one power-of-two subsampling step.
     *
     * `inSampleSize` is measured first against the bounds-only pass, so the full
     * bitmap is never materialised at its original size.
     */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sample = 1
        while (longEdge / sample > MAX_LONG_EDGE_PX) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Re-encodes at falling quality until it fits, giving up rather than
     * producing something unreadable.
     *
     * Returning null here is what the "reject if still over" half of the policy
     * looks like: below [MIN_JPEG_QUALITY] the image is artefact soup, and
     * sending it would waste the turn.
     */
    private fun compressToFit(bitmap: Bitmap, maxBytes: Long): ByteArray? {
        var quality = INITIAL_JPEG_QUALITY
        while (quality >= MIN_JPEG_QUALITY) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            if (bytes.size <= maxBytes) return bytes
            quality -= JPEG_QUALITY_STEP
        }
        return null
    }

    private fun dataUrl(mimeType: String, bytes: ByteArray): String =
        "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun renameToJpeg(name: String): String =
        name.substringBeforeLast('.', name).ifBlank { "image" } + ".jpg"

    private companion object {
        const val JPEG_MIME_TYPE = "image/jpeg"

        /**
         * The picker's own ceiling. The caller's remaining-slot count is applied
         * after the fact, because the contract takes a fixed maximum and the
         * per-message limit is what actually governs.
         */
        const val MAX_SELECTABLE = 8

        /**
         * Enough for a provider to read UI text in a screenshot, small enough
         * that a phone photo lands well under the send limit.
         */
        const val MAX_LONG_EDGE_PX = 2560

        const val INITIAL_JPEG_QUALITY = 92
        const val MIN_JPEG_QUALITY = 45
        const val JPEG_QUALITY_STEP = 12
    }
}
