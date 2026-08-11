package com.magpie.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * API key at rest: encrypted with a Tink AEAD whose keyset is itself wrapped
 * by a master key held in the Android Keystore. The resulting ciphertext is
 * what SettingsRepository stores in DataStore. Deliberately not
 * EncryptedSharedPreferences (deprecated April 2025).
 */
object ApiKeyCrypto {

    private const val KEYSET_NAME = "magpie_api_keyset"
    private const val PREF_FILE = "magpie_tink_keyset"
    private const val MASTER_KEY_URI = "android-keystore://magpie_master_key"
    private const val AAD = "magpie-api-key"

    @Volatile
    private var cached: Aead? = null

    private fun aead(context: Context): Aead = cached ?: synchronized(this) {
        cached ?: run {
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            handle.getPrimitive(Aead::class.java).also { cached = it }
        }
    }

    fun encrypt(context: Context, plainKey: String): String {
        val bytes = aead(context).encrypt(plainKey.toByteArray(Charsets.UTF_8), AAD.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Null on any failure — a corrupt ciphertext behaves like "no key set". */
    fun decrypt(context: Context, cipherB64: String): String? = try {
        if (cipherB64.isEmpty()) null
        else aead(context)
            .decrypt(Base64.decode(cipherB64, Base64.NO_WRAP), AAD.toByteArray())
            .toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
