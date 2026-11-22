package com.rovexquant.app.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only the short-lived app session token, encrypted with an Android Keystore AES key.
 * The account password is never persisted. Connection URL and username are convenience metadata.
 */
object QuantSessionStore {
    private const val PREFS = "quant_secure_session"
    private const val KEY_ALIAS = "quant_app_session_aes_v1"
    private const val URL_KEY = "backend_url"
    private const val USERNAME_KEY = "username"
    private const val TOKEN_KEY = "encrypted_access_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    data class SavedConnection(val baseUrl: String, val username: String)

    fun save(context: Context, session: QuantSession, username: String) {
        require(session.baseUrl.startsWith("https://")) { "Only HTTPS backend URLs can be saved." }
        val encrypted = encrypt(session.accessToken.toByteArray(Charsets.UTF_8))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(URL_KEY, session.baseUrl)
            .putString(USERNAME_KEY, username.trim())
            .putString(TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): QuantSession? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(URL_KEY, null)?.trim()?.trimEnd('/')
        val encoded = prefs.getString(TOKEN_KEY, null)
        if (baseUrl.isNullOrBlank() || !baseUrl.startsWith("https://") || encoded.isNullOrBlank()) return null
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val token = decrypt(encrypted).toString(Charsets.UTF_8)
            if (token.isBlank()) throw IllegalStateException("Stored session is empty.")
            QuantSession(baseUrl, token)
        } catch (_: Exception) {
            // Never fall back to plaintext or keep an unreadable credential.
            prefs.edit().remove(TOKEN_KEY).apply()
            null
        }
    }

    fun loadConnection(context: Context): SavedConnection {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SavedConnection(
            baseUrl = prefs.getString(URL_KEY, "").orEmpty(),
            username = prefs.getString(USERNAME_KEY, "").orEmpty()
        )
    }

    fun clearSession(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(TOKEN_KEY).apply()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        return cipher.iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 12) { "Stored session payload is invalid." }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
