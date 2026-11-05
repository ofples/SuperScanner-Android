package tools.superscanner.clipboardhelper

import android.graphics.Bitmap
import tools.superscanner.clipboard.ClipboardPairInfo

internal data class HelperUiState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val pairInfo: ClipboardPairInfo? = null,
    val pendingFingerprint: String? = null,
    val pendingShortCode: String? = null,
    val approvedFingerprint: String? = null,
    val snapshotPublished: Boolean = false,
    val incomingText: String? = null,
    val error: String? = null,
    val qrBitmap: Bitmap? = null,
)
