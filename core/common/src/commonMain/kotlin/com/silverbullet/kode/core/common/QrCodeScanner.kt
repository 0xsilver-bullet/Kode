package com.silverbullet.kode.core.common

/**
 * Platform QR scanning for the Add Environment screen.
 *
 * The scan produces a raw payload only; parsing and validation stay in
 * [PairingInput] so every pairing path — typed, pasted, scanned — is judged by
 * the same rules. Scanning also never auto-connects: as in T3 Code mobile, a
 * successful scan fills the form and leaves the user to submit it.
 */
interface QrCodeScanner {

    /** Whether this platform can scan at all; hides the scan button when false. */
    val isAvailable: Boolean

    suspend fun scan(): QrScanOutcome
}

sealed interface QrScanOutcome {
    /** A code was scanned; [payload] is its raw text. */
    data class Scanned(val payload: String) : QrScanOutcome

    /** The user backed out of the scanner. Not an error; show nothing. */
    data object Cancelled : QrScanOutcome

    data class Failed(val message: String) : QrScanOutcome
}

/** Default binding for hosts without a scanner implementation. */
class UnavailableQrCodeScanner : QrCodeScanner {
    override val isAvailable: Boolean = false

    override suspend fun scan(): QrScanOutcome =
        QrScanOutcome.Failed("QR scanning is not available on this device.")
}
