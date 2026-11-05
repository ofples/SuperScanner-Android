package tools.superscanner.nativelink

import org.junit.Assert.assertEquals
import org.junit.Test

class PressedKeyLedgerTest {
    @Test fun canceledTypingSequenceReleasesOnlySuccessfulUnmatchedDowns() {
        val ledger = PressedKeyLedger()
        ledger.down(29, 1) // Sent, then the caller is canceled before keyUp.
        ledger.down(30, 0)
        ledger.up(30)
        val release = mutableListOf<String>()
        ledger.release { code, meta ->
            release += NativeFrames.keyFrame(false, code, meta).decodeToString()
            true
        }
        assertEquals(listOf("KEY_UP_EVENT|32797|1"), release)
        ledger.release { _, _ -> throw AssertionError("Already released") }
    }

    @Test fun failedReleaseRemainsAvailableForSameRouteCleanup() {
        val ledger = PressedKeyLedger()
        ledger.down(29, 0)
        ledger.release { _, _ -> false }
        val later = mutableListOf<Int>()
        ledger.release { code, _ -> later += code; true }
        assertEquals(listOf(29), later)
    }
}
