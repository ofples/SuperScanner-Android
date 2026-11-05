package tools.superscanner.clipboard

import java.net.URI
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Public helper address and exact TLS certificate pin. The code is not an authorization secret. */
data class ClipboardPairInfo(val host: String, val port: Int, val serverFingerprint: String) {
    init {
        require(IPv4.parse(host) != null) { "Expected a numeric IPv4 address" }
        require(port in 1..65535) { "Invalid port" }
        require(FINGERPRINT.matches(serverFingerprint)) { "Invalid certificate fingerprint" }
    }

    fun encode(): String = "superscanner-clipboard://pair?v=1&host=$host&port=$port&fp=$serverFingerprint"

    /** Short authentication string for a physical comparison on both screens. */
    fun approvalCode(clientFingerprint: String): String {
        require(FINGERPRINT.matches(clientFingerprint)) { "Invalid client certificate fingerprint" }
        val input = "SuperScanner Clipboard approval v1\n$serverFingerprint\n$clientFingerprint"
            .toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.getInstance("SHA-256").digest(input)
            .take(6).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    companion object {
        private val FINGERPRINT = Regex("[a-f0-9]{64}")

        fun parse(code: String): ClipboardPairInfo {
            require(code.length <= 256) { "Pairing code is too long" }
            val uri = URI(code)
            require(!uri.isOpaque && uri.scheme == "superscanner-clipboard" && uri.host == "pair" &&
                uri.port == -1 && uri.fragment == null && uri.userInfo == null && uri.rawPath.isNullOrEmpty())
            val fields = uri.rawQuery?.split('&')?.map {
                val pair = it.split('=', limit = 2)
                require(pair.size == 2 && pair[0] in setOf("v", "host", "port", "fp"))
                pair[0] to pair[1]
            } ?: throw IllegalArgumentException("Missing pairing fields")
            require(fields.size == 4 && fields.map { it.first }.toSet().size == 4)
            val map = fields.toMap()
            require(map["v"] == "1") { "Unsupported pairing version" }
            return ClipboardPairInfo(map.getValue("host"), map.getValue("port").toInt(), map.getValue("fp"))
        }
    }
}

internal object IPv4 {
    fun parse(host: String): Long? {
        val pieces = host.split('.')
        if (pieces.size != 4) return null
        var value = 0L
        for (piece in pieces) {
            if (piece.isEmpty() || piece.length > 3 || (piece.length > 1 && piece[0] == '0') || !piece.all(Char::isDigit)) return null
            val octet = piece.toIntOrNull() ?: return null
            if (octet > 255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun isPrivate(host: String): Boolean {
        val value = parse(host) ?: return false
        return (value ushr 24 == 10L) ||
            (value ushr 20 == 0xac1L) ||
            (value ushr 16 == 0xc0a8L)
    }

    fun sameSubnet(host: String, local: String, prefix: Int): Boolean {
        val target = parse(host) ?: return false
        val source = parse(local) ?: return false
        if (prefix !in 1..32 || !isPrivate(host) || !isPrivate(local)) return false
        val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
        return (target and mask) == (source and mask)
    }
}
