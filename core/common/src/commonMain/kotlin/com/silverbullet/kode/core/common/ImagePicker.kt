package com.silverbullet.kode.core.common

/**
 * Platform image picking for the composer's attach affordance.
 *
 * Follows the capability pattern [QrCodeScanner] establishes: interface in
 * commonMain, an unavailable default in DI, the real implementation bound by
 * the platform module. A host with no picker simply shows no attach button,
 * rather than a control that fails when tapped.
 *
 * Encoding is the platform's job, not the caller's. The picker hands back a
 * ready-to-send `data:` URL because only the platform can decode, re-encode and
 * downscale an image — and downscaling is not optional: a modern phone photo
 * routinely exceeds the server's per-image limit, and rejecting it would make
 * the feature useless on exactly the images people want to send.
 */
interface ImagePicker {

    /** Whether this platform can pick at all; hides the attach button when false. */
    val isAvailable: Boolean

    /**
     * Opens the system picker.
     *
     * [maxCount] is the number of *remaining* slots, not the per-message limit —
     * the caller subtracts what is already attached. Implementations must not
     * return more than that many images.
     *
     * [maxBytes] bounds each encoded image. An implementation should downscale
     * to fit before giving up on one, and report anything it had to drop
     * through [ImagePickOutcome.Picked.warning] rather than failing the whole
     * pick: one unusable image must not discard the others.
     */
    suspend fun pick(
        maxCount: Int,
        maxBytes: Long,
        supportedMimeTypes: Set<String>,
    ): ImagePickOutcome
}

/**
 * One picked image, already encoded for the wire.
 *
 * [previewUri] is a platform-local handle for rendering the thumbnail (a
 * `content://` URI on Android) — deliberately *not* the data URL, so the
 * composer's strip does not push megabytes of base64 through the image loader
 * on every recomposition.
 */
class PickedImage(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataUrl: String,
    val previewUri: String,
)

sealed interface ImagePickOutcome {
    /**
     * Images were chosen. [images] may be empty when every candidate was
     * rejected, in which case [warning] says why.
     */
    data class Picked(
        val images: List<PickedImage>,
        /** A partial failure: some images were skipped, the rest are usable. */
        val warning: String? = null,
    ) : ImagePickOutcome

    /** The user backed out. Not an error; show nothing. */
    data object Cancelled : ImagePickOutcome

    data class Failed(val message: String) : ImagePickOutcome
}

/** Default binding for hosts without a picker implementation. */
class UnavailableImagePicker : ImagePicker {
    override val isAvailable: Boolean = false

    override suspend fun pick(
        maxCount: Int,
        maxBytes: Long,
        supportedMimeTypes: Set<String>,
    ): ImagePickOutcome =
        ImagePickOutcome.Failed("Attaching images is not available on this device.")
}
