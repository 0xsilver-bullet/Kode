package com.silverbullet.kode.voiceserver

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a QR code as terminal text using half-block characters (two matrix rows per
 * text line), the same trick t3's `pair` command uses to keep the code scannable at
 * terminal font sizes.
 */
object TerminalQr {

    fun render(content: String): String {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )
        val out = StringBuilder()
        var y = 0
        while (y < matrix.height) {
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = y + 1 < matrix.height && matrix.get(x, y + 1)
                // Inverted (dark terminal background = "black" modules) reads more reliably,
                // so emit blocks for *unset* modules.
                out.append(
                    when {
                        !top && !bottom -> '█'
                        !top && bottom -> '▀'
                        top && !bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            out.append('\n')
            y += 2
        }
        return out.toString()
    }
}
