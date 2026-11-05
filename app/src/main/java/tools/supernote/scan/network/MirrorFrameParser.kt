package tools.supernote.scan.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

internal class MirrorFrameParser(
    input: InputStream,
    boundary: String,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val frameTimeoutMillis: Long = DEFAULT_FRAME_TIMEOUT_MILLIS,
) {
    private val input = if (input is BufferedInputStream) input else BufferedInputStream(input, 32 * 1024)
    private val boundaryMarker = "--${boundary.trim().removeSurrounding("\"")}".toByteArray(Charsets.US_ASCII)

    @Throws(MirrorStreamException::class)
    fun readFrame(): EncodedFrame {
        require(boundaryMarker.size > 2) { "Multipart boundary is missing." }
        val deadline = System.nanoTime() + frameTimeoutMillis * 1_000_000
        val headers = readPartHeaders(deadline)
        val declaredLength = CONTENT_LENGTH.find(headers)?.groupValues?.get(1)?.toLongOrNull()
        if (declaredLength != null && (declaredLength <= 0 || declaredLength > maxFrameBytes)) {
            throw MirrorStreamException("The mirror frame has an invalid size.")
        }
        val frame = if (declaredLength != null) {
            readExactly(declaredLength.toInt(), deadline)
        } else {
            readUntil("\r\n".toByteArray(Charsets.US_ASCII) + boundaryMarker, deadline)
        }
        return EncodedFrame(frame, imageMime(frame))
    }

    private fun readPartHeaders(deadline: Long): String {
        while (true) {
            val line = readLine(deadline, MAX_HEADER_BYTES)
            val normalized = line.trim().lowercase(Locale.ROOT)
            if (line.toByteArray(Charsets.ISO_8859_1).contentEquals(boundaryMarker) || normalized == boundaryMarker.toString(Charsets.US_ASCII).lowercase(Locale.ROOT)) {
                continue
            }
            if (normalized.startsWith("content-type:")) {
                val headerLines = StringBuilder(line).append("\r\n")
                while (true) {
                    val next = readLine(deadline, MAX_HEADER_BYTES)
                    if (next.isEmpty()) break
                    headerLines.append(next).append("\r\n")
                    if (headerLines.length > MAX_HEADER_BYTES) throw MirrorStreamException("The mirror frame headers are too large.")
                }
                return validateImageHeader(headerLines.toString())
            }
        }
    }

    private fun validateImageHeader(headers: String): String {
        if (!CONTENT_TYPE.containsMatchIn(headers)) {
            throw MirrorStreamException("The mirror did not provide an image frame.")
        }
        return headers
    }

    private fun readLine(deadline: Long, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= maxBytes) {
            val byte = readByte(deadline)
            if (previous == '\r'.code && byte == '\n'.code) {
                val bytes = output.toByteArray()
                return bytes.copyOf(bytes.size - 1).toString(Charsets.ISO_8859_1)
            }
            output.write(byte)
            previous = byte
        }
        throw MirrorStreamException("The mirror frame headers are too large.")
    }

    private fun readExactly(length: Int, deadline: Long): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            checkDeadline(deadline)
            val read = input.read(result, offset, minOf(length - offset, 32 * 1024))
            if (read < 0) throw MirrorStreamException("The mirror frame ended before the image was complete.")
            offset += read
        }
        return result
    }

    private fun readUntil(delimiter: ByteArray, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val pending = ArrayDeque<Byte>()
        val suffix = ArrayDeque<Byte>()
        val prefix = ByteArray(PNG_MAGIC.size)
        var prefixLength = 0
        while (output.size() <= maxFrameBytes) {
            val next = readByte(deadline).toByte()
            if (prefixLength < prefix.size) prefix[prefixLength++] = next
            suffix.addLast(next)
            if (suffix.size > PNG_END.size) suffix.removeFirst()
            pending.addLast(next)
            if (isCompleteImageSuffix(prefix, prefixLength, suffix)) {
                return output.toByteArray() + pending.map { it }.toByteArray()
            }
            while (!startsWith(pending, delimiter) && pending.size > delimiter.size) {
                val written = pending.removeFirst()
                output.write(written.toInt())
                if (output.size() > maxFrameBytes) throw MirrorStreamException("The mirror frame exceeds the size limit.")
            }
            if (startsWith(pending, delimiter)) return output.toByteArray()
        }
        throw MirrorStreamException("The mirror frame exceeds the size limit.")
    }

    private fun readByte(deadline: Long): Int {
        checkDeadline(deadline)
        val value = input.read()
        if (value < 0) throw MirrorStreamException("The mirror stream ended before a complete frame arrived.")
        checkDeadline(deadline)
        return value
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() > deadline) throw MirrorStreamException("No complete mirror frame arrived before the timeout.")
    }

    private fun startsWith(queue: ArrayDeque<Byte>, prefix: ByteArray): Boolean =
        queue.size >= prefix.size && prefix.indices.all { queue.elementAt(it) == prefix[it] }

    private fun isCompleteImageSuffix(prefix: ByteArray, prefixLength: Int, suffix: ArrayDeque<Byte>): Boolean {
        if (prefixLength >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() &&
            suffix.size >= 2 && suffix.elementAt(suffix.size - 2) == 0xff.toByte() && suffix.last() == 0xd9.toByte()) return true
        return prefixLength == PNG_MAGIC.size && prefix.contentEquals(PNG_MAGIC) &&
            suffix.size == PNG_END.size && PNG_END.indices.all { suffix.elementAt(it) == PNG_END[it] }
    }

    private fun imageMime(bytes: ByteArray): String = when {
        bytes.size >= PNG_MAGIC.size + PNG_END.size &&
            bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC) &&
            bytes.copyOfRange(bytes.size - PNG_END.size, bytes.size).contentEquals(PNG_END) -> "image/png"
        bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte() -> "image/jpeg"
        else -> throw MirrorStreamException("The mirror frame is incomplete or is not a supported PNG or JPEG image.")
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 12_000_000
        const val DEFAULT_FRAME_TIMEOUT_MILLIS = 12_000L
        private const val MAX_HEADER_BYTES = 8 * 1024
        private val CONTENT_TYPE = Regex("content-type\\s*:\\s*image/(?:jpeg|png)", RegexOption.IGNORE_CASE)
        private val CONTENT_LENGTH = Regex("content-length\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val PNG_END = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44.toByte(), 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
    }
}

internal data class EncodedFrame(val bytes: ByteArray, val mimeType: String)

class MirrorStreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
