package com.silverbullet.kode.platform

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.silverbullet.kode.feature.connection.domain.QrCodeScanner
import com.silverbullet.kode.feature.connection.domain.QrScanOutcome
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * QR scanning via Google's code scanner.
 *
 * Chosen over an in-app camera preview deliberately: the scan UI is provided by
 * Play services in its own activity, so the app needs no camera permission, no
 * permission-denied dialogs, and no camera dependency — the whole
 * permission-handling flow T3 Code mobile implements around `expo-camera`
 * becomes unnecessary. The behavioural contract is preserved: the scan returns
 * a raw payload and never auto-connects.
 */
class AndroidQrCodeScanner(private val context: Context) : QrCodeScanner {

    override val isAvailable: Boolean = true

    override suspend fun scan(): QrScanOutcome {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)

        return suspendCancellableCoroutine { continuation ->
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    continuation.resume(QrScanOutcome.Scanned(barcode.rawValue.orEmpty()))
                }
                .addOnCanceledListener {
                    continuation.resume(QrScanOutcome.Cancelled)
                }
                .addOnFailureListener { failure ->
                    continuation.resume(
                        QrScanOutcome.Failed(
                            failure.message ?: "QR scanning is unavailable on this device.",
                        ),
                    )
                }
        }
    }
}
