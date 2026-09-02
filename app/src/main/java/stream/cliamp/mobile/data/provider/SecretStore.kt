package stream.cliamp.mobile.data.provider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with the key held in the Android Keystore, so the key material
 * never enters the app's process and cannot be pulled out of a backup or an
 * adb dump.
 *
 * The app's settings live in a plain preferences DataStore, which is fine for
 * a palette choice and completely wrong for a music server password: that file
 * is readable by root and can be swept into cloud backup. Provider secrets go
 * through here instead.
 *
 * androidx.security:security-crypto would be the obvious library for this and
 * is deprecated, so this talks to the Keystore directly. It is about fifty
 * lines and adds no dependency.
 */
object SecretStore {

    private const val KEY_ALIAS = "cliamp.provider.secrets.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TAG = "cliamp/secrets"

    /** Marks a stored value as ciphertext so plaintext migrations stay readable. */
    private const val PREFIX = "enc.v1:"

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrElse {
            // Failing closed would lock the user out of their own server, so
            // this degrades loudly instead of silently storing plaintext.
            Log.e(TAG, "encrypt failed: ${it.message}")
            throw it
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            }
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
        }.getOrElse {
            Log.w(TAG, "decrypt failed: ${it.message}")
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // no setUserAuthenticationRequired: playback has to survive
                    // a locked screen, and the threat here is file access, not
                    // someone holding the unlocked phone
                    .build()
            )
        }.generateKey()
    }
}
