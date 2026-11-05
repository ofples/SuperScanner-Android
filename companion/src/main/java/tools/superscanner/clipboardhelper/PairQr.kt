package tools.superscanner.clipboardhelper

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import tools.superscanner.clipboard.ClipboardPairInfo

internal object PairQr {
    fun encode(info: ClipboardPairInfo): String = info.encode()

    fun render(info: ClipboardPairInfo, edge: Int = 560): Bitmap {
        val matrix = matrix(info, edge)
        val pixels = IntArray(edge * edge) { index ->
            if (matrix[index % edge, index / edge]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(pixels, edge, edge, Bitmap.Config.ARGB_8888)
    }

    fun matrix(info: ClipboardPairInfo, edge: Int) = run {
        require(edge in 256..1024)
        QRCodeWriter().encode(encode(info), BarcodeFormat.QR_CODE, edge, edge, mapOf(EncodeHintType.MARGIN to 2))
    }
}
