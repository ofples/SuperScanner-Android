package tools.superscanner.nativelink

/** Tracks only key-down frames that were successfully written. Caller serializes access. */
internal class PressedKeyLedger {
    private val keys = linkedMapOf<Int, Int>()

    fun down(keyCode: Int, metaState: Int) { keys[keyCode] = metaState }
    fun up(keyCode: Int) { keys.remove(keyCode) }
    fun clear() { keys.clear() }

    fun release(sendUp: (keyCode: Int, metaState: Int) -> Boolean) {
        for ((keyCode, metaState) in keys.toList()) {
            if (sendUp(keyCode, metaState)) keys.remove(keyCode)
        }
    }
}
