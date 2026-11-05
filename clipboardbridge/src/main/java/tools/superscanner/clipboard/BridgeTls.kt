package tools.superscanner.clipboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal object BridgeTls {
    private const val SERVER_ALIAS = "super_scanner_clipboard_server_v2"
    private const val CLIENT_ALIAS = "super_scanner_clipboard_client_v2"

    fun identity(server: Boolean): Identity {
        val alias = if (server) SERVER_ALIAS else CLIENT_ALIAS
        return identityForAlias(alias, if (server) "Helper" else "Phone")
    }

    /** Isolated instrumentation identity. Never used by production constructors. */
    internal fun testIdentity(): Identity = identityForAlias("super_scanner_clipboard_test_client_v2", "Test Client")

    @Synchronized private fun identityForAlias(alias: String, label: String): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val now = System.currentTimeMillis()
            // Conscrypt can perform TLS RSA signing through a raw private-key cipher.
            // Authorize that platform operation as documented for TLS server keys.
            val spec = KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=SuperScanner Clipboard $label"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: throw BridgeException("Clipboard identity unavailable")
        certificate.checkValidity()
        val key = store.getKey(alias, null) as? PrivateKey ?: throw BridgeException("Clipboard identity unavailable")
        return Identity(SingleIdentityKeyManager(alias, key, certificate), fingerprint(certificate))
    }

    data class Identity(val keyManager: X509ExtendedKeyManager, val fingerprint: String)

    fun context(identity: Identity, trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf<KeyManager>(identity.keyManager), arrayOf<TrustManager>(trust), SecureRandom())
    }

    private class SingleIdentityKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        private fun supports(keyType: String?): Boolean = keyType?.startsWith("RSA") == true
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
            if (supports(keyType)) arrayOf(alias) else null
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? =
            if (keyTypes?.any(::supports) == true) alias else null
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
            if (supports(keyType)) arrayOf(alias) else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? =
            if (supports(keyType)) alias else null
        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? =
            if (keyTypes?.any(::supports) == true) alias else null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? =
            if (supports(keyType)) alias else null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == this.alias) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
    }

    fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun shortCode(fingerprint: String): String = fingerprint.take(12).uppercase()

    private fun validSelfSigned(chain: Array<out X509Certificate>?): X509Certificate {
        if (chain == null || chain.size != 1) throw CertificateException("Expected one self-signed certificate")
        val cert = chain[0]
        cert.checkValidity()
        if (cert.subjectX500Principal != cert.issuerX500Principal) throw CertificateException("Certificate is not self-signed")
        cert.verify(cert.publicKey)
        if (cert.publicKey.algorithm != "RSA" || (cert.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength() < 2048) {
            throw CertificateException("Unsupported certificate key")
        }
        return cert
    }

    /** Server handshake accepts a self-signed certificate only to identify a *candidate*; requests still require approval. */
    val candidateClientTrust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { validSelfSigned(chain) }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Server pin required")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun pinnedServerTrust(expected: String): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client trust unavailable")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val actual = fingerprint(validSelfSigned(chain))
            if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
                throw CertificateException("Helper identity mismatch")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
