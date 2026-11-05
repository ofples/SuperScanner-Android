package tools.superscanner.clipboardhelper

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test
import tools.superscanner.clipboard.ClipboardPairInfo

class PairQrTest {
    @Test fun qrContainsOnlyTheEncodedPairAddressAndServerPin() {
        val pair = ClipboardPairInfo("192.168.1.24", 43127, "a".repeat(64))
        val matrix = PairQr.matrix(pair, 400)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else -1
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        assertEquals(pair.encode(), decoded)
        assertEquals(pair, ClipboardPairInfo.parse(decoded))
    }
}
