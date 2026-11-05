package tools.superscanner.clipboard

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class BridgeException(message: String) : IOException(message)

internal object BridgeWire {
    const val VERSION = 1
    const val MAX_FRAME = 128 * 1024
    const val MAX_TEXT = 64 * 1024

    data class Message(val op: String?, val status: String?, val text: String?)

    fun textBytes(text: String): ByteArray {
        require(text.length <= MAX_TEXT) { "Text exceeds UTF-8 limit" }
        val encoded = try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid Unicode text") }
        require(encoded.remaining() <= MAX_TEXT) { "Text exceeds UTF-8 limit" }
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return bytes
    }

    fun write(output: DataOutputStream, op: String? = null, status: String? = null, text: String? = null) {
        require((op == null) != (status == null))
        val objectValue = JSONObject().put("v", VERSION)
        if (op != null) objectValue.put("op", op)
        if (status != null) objectValue.put("status", status)
        if (text != null) {
            textBytes(text)
            objectValue.put("text", text)
        }
        val bytes = objectValue.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FRAME)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun read(input: DataInputStream, request: Boolean): Message {
        val size = input.readInt()
        if (size !in 1..MAX_FRAME) throw BridgeException("Invalid message length")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val content = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw BridgeException("Invalid UTF-8") }
        val values = mutableMapOf<String, String>()
        val allowed = if (request) setOf("v", "op", "text") else setOf("v", "status", "text")
        try {
            JsonReader(StringReader(content)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name !in allowed || name in values) throw BridgeException("Invalid message fields")
                    if (reader.peek() != (if (name == "v") JsonToken.NUMBER else JsonToken.STRING)) {
                        throw BridgeException("Invalid message type")
                    }
                    values[name] = reader.nextString()
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw BridgeException("Trailing message data")
            }
        } catch (error: Exception) {
            if (error is BridgeException) throw error
            throw BridgeException("Invalid message")
        }
        if (values["v"] != VERSION.toString()) throw BridgeException("Unsupported message")
        val discriminator = if (request) "op" else "status"
        val kind = values[discriminator] ?: throw BridgeException("Invalid message type")
        if (kind.length > 24 || kind.isEmpty()) throw BridgeException("Invalid message type")
        val text = values["text"]
        if (text != null && textBytes(text).size > MAX_TEXT) throw BridgeException("Text too long")
        if (request) {
            if (kind !in setOf("pair", "status", "get", "put") || (kind != "put" && text != null) || (kind == "put" && text == null)) {
                throw BridgeException("Invalid operation")
            }
        } else if (kind !in setOf("pending", "approved", "rejected", "unpaired", "ok", "error") || (kind != "ok" && text != null)) {
            throw BridgeException("Invalid response")
        }
        return Message(if (request) kind else null, if (request) null else kind, text)
    }
}
