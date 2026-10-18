package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.content.Context
import java.util.UUID

object PasswordStoreSmoke {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val id = "smoke-${UUID.randomUUID()}"
        val url = "https://example.invalid"
        val secret = "smoke-only-암호-123"
        val prefs = context.getSharedPreferences("encrypted_passwords", Context.MODE_PRIVATE)
        val store = PasswordStore(context)
        try {
            check(store.read(id,url) == null)
            store.write(id,url,secret)
            val first = prefs.getString(id,null)!!
            check(!first.contains(secret))
            check(PasswordStore(context).read(id,url) == secret)
            store.write(id,url,secret)
            check(first != prefs.getString(id,null)) { "IV was reused" }
            check(runCatching { store.read(id,"https://other.invalid") }.isFailure)
            check(prefs.edit().putString("$id-copy",first).commit())
            check(runCatching { store.read("$id-copy",url) }.isFailure)
            val parts = first.split(":").toMutableList()
            val ciphertext = android.util.Base64.decode(parts[2],android.util.Base64.NO_WRAP)
            ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
            parts[2] = android.util.Base64.encodeToString(ciphertext,android.util.Base64.NO_WRAP)
            check(prefs.edit().putString(id,parts.joinToString(":")).commit())
            check(runCatching { store.read(id,url) }.isFailure) { "Tampered record accepted" }
            store.write(id,url,"")
            check(store.read(id,url) == "")
            store.remove(id)
            check(PasswordStore(context).read(id,url) == null)
        } finally { store.remove(id); store.remove("$id-copy") }
    }
}
