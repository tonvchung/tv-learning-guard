package com.icybard.learningguard.parent

import android.content.Context
import android.util.Base64
import com.icybard.learningguard.data.GuardStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parent_auth", Context.MODE_PRIVATE)
    private val store = GuardStore(context)
    private val random = SecureRandom()

    val isConfigured: Boolean get() = store.pinConfigured && prefs.contains("pinHash")

    fun configure(pin: String) {
        require(pin.matches(Regex("\\d{6}"))) { "PIN must contain exactly six digits" }
        require(!isConfigured) { "PIN is already configured" }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(pin, salt)
        prefs.edit()
            .putString("pinSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pinHash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        store.pinConfigured = true
    }

    fun verify(pin: String): Boolean {
        if (!isConfigured || !pin.matches(Regex("\\d{6}"))) return false
        val salt = Base64.decode(prefs.getString("pinSalt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("pinHash", ""), Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
