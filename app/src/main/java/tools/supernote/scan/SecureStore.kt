package tools.supernote.scan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted store for the API key and SIWC session. Callers never log values returned here. */
internal class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scan_credentials_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(name: String): String? = synchronized(lock) {
        val packed = prefs.getString(name, null) ?: return null
        try {
            val parts = packed.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (error: Exception) {
            // Authentication-tag failure or malformed ciphertext is treated as unavailable data.
            throw IllegalStateException("Saved secure data is unavailable.", error)
        }
    }

    fun write(name: String, value: String?) = synchronized(lock) {
        if (value == null) {
            check(prefs.edit().remove(name).commit()) { "Could not update secure storage." }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString(name, packed).commit()) { "Could not update secure storage." }
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "tools.supernote.scan.credentials.v1"
        val KEY_LOCK = Any()
    }
}
