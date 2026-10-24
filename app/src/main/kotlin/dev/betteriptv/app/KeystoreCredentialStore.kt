package dev.betteriptv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.betteriptv.core.CredentialStore
import dev.betteriptv.core.ProviderLogin
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Provider login in app-private preferences, encrypted with an AES key that lives in the
 * Android Keystore and never leaves it.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("provider_login", Context.MODE_PRIVATE)

    override fun load(): ProviderLogin? {
        val iv = prefs.getString(IV, null) ?: return null
        val sealed = prefs.getString(SEALED, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8))
            ProviderLogin(json.getString("address"), json.getString("username"), json.getString("password"))
        } catch (e: Exception) {
            // The key is gone (e.g. app data restored elsewhere): ask for the login again.
            null
        }
    }

    override fun save(login: ProviderLogin) {
        val json = JSONObject()
            .put("address", login.address)
            .put("username", login.username)
            .put("password", login.password)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "provider_login"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = "iv"
        const val SEALED = "sealed"
    }
}
