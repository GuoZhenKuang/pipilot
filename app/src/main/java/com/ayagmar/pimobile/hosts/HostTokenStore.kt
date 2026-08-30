package com.ayagmar.pimobile.hosts

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface HostTokenStore {
    val requiresTokenReentry: Boolean
        get() = false

    fun hasToken(hostId: String): Boolean

    fun getToken(hostId: String): String?

    fun setToken(
        hostId: String,
        token: String,
    )

    fun clearToken(hostId: String)
}

class KeystoreHostTokenStore(
    private val context: Context,
    private val cipher: TokenCipher = TokenCipher(androidKeystoreKey()),
) : HostTokenStore {
    private val migrationPreferences =
        context.getSharedPreferences(MIGRATION_PREFS_FILE, Context.MODE_PRIVATE)
    private val preferences: SharedPreferences =
        context.getSharedPreferences(TOKENS_PREFS_FILE, Context.MODE_PRIVATE)

    override val requiresTokenReentry: Boolean = resetLegacyTokensIfNeeded()

    override fun hasToken(hostId: String): Boolean = getToken(hostId) != null

    override fun getToken(hostId: String): String? {
        val encrypted = preferences.getString(tokenKey(hostId), null) ?: return null
        return runCatching { cipher.decrypt(encrypted) }
            .onFailure { preferences.edit { remove(tokenKey(hostId)) } }
            .getOrNull()
    }

    override fun setToken(
        hostId: String,
        token: String,
    ) {
        preferences.edit { putString(tokenKey(hostId), cipher.encrypt(token)) }
    }

    override fun clearToken(hostId: String) {
        preferences.edit { remove(tokenKey(hostId)) }
    }

    private fun resetLegacyTokensIfNeeded(): Boolean {
        if (migrationPreferences.getBoolean(MIGRATION_COMPLETE_KEY, false)) return false

        val legacyPreferencesFile =
            File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_TOKENS_PREFS_FILE.xml")
        val legacyPreferencesExist = legacyPreferencesFile.exists()
        if (legacyPreferencesExist) {
            context.deleteSharedPreferences(LEGACY_TOKENS_PREFS_FILE)
        }
        migrationPreferences.edit { putBoolean(MIGRATION_COMPLETE_KEY, true) }
        return legacyPreferencesExist
    }

    private fun tokenKey(hostId: String): String = "token_$hostId"

    companion object {
        private const val KEY_ALIAS = "pi_mobile_host_tokens_v2"
        private const val LEGACY_TOKENS_PREFS_FILE = "pi_mobile_host_tokens_secure"
        private const val TOKENS_PREFS_FILE = "pi_mobile_host_tokens_v2"
        private const val MIGRATION_PREFS_FILE = "pi_mobile_token_migration"
        private const val MIGRATION_COMPLETE_KEY = "legacy_tokens_reset_v2"
        private const val KEY_SIZE_BITS = 256

        private fun androidKeystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            return keyGenerator.generateKey()
        }
    }
}

class TokenCipher(
    private val key: SecretKey,
) {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(payload: String): String {
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size > IV_LENGTH_BYTES) { "加密令牌数据无效" }
        val iv = bytes.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = bytes.copyOfRange(IV_LENGTH_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
