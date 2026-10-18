package kr.co.nexacore.jetkvm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists only authenticated ciphertext and its IV; the key stays in Android Keystore. */
class PasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_passwords", Context.MODE_PRIVATE)
    private val alias = "jetkvm.passwords.v1"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Password key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized
    fun read(id: String, url: String): String? {
        val record = prefs.getString(id, null) ?: return null
        val parts = record.split(":")
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        cipher.updateAAD("$id\n$url".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }

    @Synchronized
    fun write(id: String, url: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD("$id\n$url".toByteArray(Charsets.UTF_8))
        val plain = password.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val record = "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        check(prefs.edit().putString(id, record).commit()) { "Password save failed" }
    }

    @Synchronized
    fun remove(id: String) {
        check(prefs.edit().remove(id).commit()) { "Password removal failed" }
    }
}
