package tools.superscanner.nativelink

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Limits imposed by this client; the inspected firmware does not enforce equivalent limits. */
const val MAX_NATIVE_CLIPBOARD_BYTES: Int = 64 * 1024

internal object NativeFrames {
    private const val MAX_PAIR_RESPONSE_BYTES = 4096
    private const val MAX_DEVICE_NAME_BYTES = 100

    fun discoveryReply(data: ByteArray, length: Int, address: Inet4Address): NativeDevice? {
        if (length !in 5..512 || !isPrivateIpv4(address)) return null
        val value = decodeUtf8(data, length) ?: return null
        val lastComma = value.lastIndexOf(',')
        if (lastComma <= 0 || value.substring(lastComma + 1) != "2") return null
        val nameComma = value.lastIndexOf(',', lastComma - 1)
        if (nameComma <= 0) return null
        val rawName = value.substring(0, nameComma)
        val rawIdentifier = value.substring(nameComma + 1, lastComma)
        if (rawName.any { it.code < 0x20 || it.code == 0x7f } ||
            rawIdentifier.any { it.code < 0x20 || it.code == 0x7f }) return null
        val name = rawName.trim()
        val identifier = rawIdentifier.trim()
        if (name.isEmpty() || name.toByteArray(StandardCharsets.UTF_8).size > MAX_DEVICE_NAME_BYTES ||
            identifier.isEmpty() || identifier.length > 100 ||
            identifier.any { it.code < 0x20 || it.code > 0x7e }) return null
        return NativeDevice(name, address.hostAddress ?: return null, identifier)
    }

    fun pairRequest(displayName: String, code: String): ByteArray {
        require(displayName.matches(Regex("SuperScanner-[A-F0-9]{8}")))
        require(code.matches(Regex("[0-9]{6}")))
        return "GET / HTTP/1.0\r\nDeviceName: $displayName\r\nStatus: 1\r\nPairCode: $code\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    fun pairCancel(displayName: String): ByteArray {
        require(displayName.matches(Regex("SuperScanner-[A-F0-9]{8}")))
        return "GET / HTTP/1.0\r\nDeviceName: $displayName\r\nStatus: 2\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    /** 200 means device approval; 201 means device refusal; anything else fails closed. */
    fun pairResponse(input: InputStream): Int? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_PAIR_RESPONSE_BYTES) {
            val next = input.read()
            if (next < 0) break
            bytes.write(next)
            if (next == '}'.code) break
        }
        if (bytes.size() == MAX_PAIR_RESPONSE_BYTES) return null
        val response = bytes.toString(StandardCharsets.ISO_8859_1.name())
        val normalized = response.replace("\r\n", "\n")
        val separator = normalized.indexOf("\n\n")
        if (separator < 0 || normalized.substringBefore('\n') != "HTTP/1.0 200 OK") return null
        val body = normalized.substring(separator + 2).trim()
        // The vendor response is JSON-like, with an unquoted key and a localized message.
        val code = Regex("^\\{code:(200|201),msg:\"[^\\r\\n\"]{0,512}\"\\}$").matchEntire(body)
            ?.groupValues?.get(1)?.toIntOrNull()
        return code
    }

    fun keyFrame(down: Boolean, keyCode: Int, metaState: Int): ByteArray {
        require(keyCode in 0..0x3fff)
        val allowedMeta = 0x100000 or 0x200000 or 0x400000 or 0x10000 or 0x1000 or 0x7
        require(metaState >= 0 && metaState and allowedMeta.inv() == 0)
        val packed = keyCode or (if (down) 0x4000 else 0x8000)
        val prefix = if (down) "KEY_DOWN_EVENT" else "KEY_UP_EVENT"
        return "$prefix|$packed|$metaState".toByteArray(StandardCharsets.US_ASCII)
    }

    fun clipboardText(text: String): ByteArray {
        require(text.isNotEmpty() && text.length <= MAX_NATIVE_CLIPBOARD_BYTES)
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        require(buffer.remaining() in 1..MAX_NATIVE_CLIPBOARD_BYTES)
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun tcpClipboardFrame(text: String): ByteArray {
        val bytes = clipboardText(text)
        val header = "TCP_STRING|${bytes.size}|".toByteArray(StandardCharsets.US_ASCII)
        return ByteArray(header.size + bytes.size).also {
            header.copyInto(it)
            bytes.copyInto(it, header.size)
        }
    }

    fun decodeUtf8(bytes: ByteArray, length: Int): String? = try {
        if (length < 0 || length > bytes.size) null else StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (_: Exception) {
        null
    }

    fun clipboardNotifyLength(message: String): Int? {
        if (!message.startsWith("CLIP_TCP_NOTIFY|")) return null
        val digits = message.removePrefix("CLIP_TCP_NOTIFY|")
        if (digits.isEmpty() || digits.length > 5 || digits.any { it !in '0'..'9' }) return null
        val length = digits.toIntOrNull() ?: return null
        return length.takeIf { it in 1..MAX_NATIVE_CLIPBOARD_BYTES }
    }

    /** The TCP fetch response has no auth and no escaping; consume only the advertised bytes. */
    fun readClipboardFetch(input: InputStream, expectedLength: Int): String? {
        if (expectedLength !in 1..MAX_NATIVE_CLIPBOARD_BYTES) return null
        val header = ByteArrayOutputStream()
        repeat(40) {
            val next = input.read()
            if (next < 0) return null
            header.write(next)
            if (next == '|'.code && header.size() > "CLIP_TCP_CONTENT|".length) {
                val text = header.toString(StandardCharsets.US_ASCII.name())
                if (!text.startsWith("CLIP_TCP_CONTENT|")) return null
                val digits = text.removePrefix("CLIP_TCP_CONTENT|").removeSuffix("|")
                if (digits.isEmpty() || digits.length > 5 || digits.any { it !in '0'..'9' }) return null
                val actual = digits.toIntOrNull() ?: return null
                if (actual != expectedLength) return null
                val bytes = ByteArray(actual)
                var offset = 0
                while (offset < actual) {
                    val count = input.read(bytes, offset, actual - offset)
                    if (count <= 0) return null
                    offset += count
                }
                if (input.read() != '\n'.code) return null
                return decodeUtf8(bytes, bytes.size)
            }
        }
        return null
    }
}
