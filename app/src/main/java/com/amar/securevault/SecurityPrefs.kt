package com.amar.securevault

import android.content.Context
import kotlin.math.min

/**
 * Non-secret settings plus the biometric-wrapped data key.
 * The wrapped key is useless without the hardware-backed Keystore key, which itself
 * needs a fresh strong-biometric authentication for every single use.
 */
class SecurityPrefs(context: Context) {
    private val p = context.getSharedPreferences("sv_prefs", Context.MODE_PRIVATE)

    val failedAttempts: Int get() = p.getInt("fails", 0)
    val lockUntil: Long get() = p.getLong("lock_until", 0L)

    var autoLockSeconds: Int
        get() = p.getInt("autolock", 30)
        set(v) {
            p.edit().putInt("autolock", v).apply()
        }

    var bioIv: String?
        get() = p.getString("bio_iv", null)
        set(v) {
            p.edit().putString("bio_iv", v).apply()
        }

    var bioBlob: String?
        get() = p.getString("bio_blob", null)
        set(v) {
            p.edit().putString("bio_blob", v).apply()
        }

    val bioConfigured: Boolean get() = bioIv != null && bioBlob != null

    fun clearBio() {
        p.edit().remove("bio_iv").remove("bio_blob").apply()
    }

    /** After 5 wrong passwords the UI enforces a growing delay (30 s, 60 s, ... max 1 h). */
    fun recordFailure(): Int {
        val n = failedAttempts + 1
        val delaySeconds = if (n >= 5) min(3600L, 30L shl (n - 5).coerceAtMost(7)) else 0L
        val until = if (delaySeconds > 0) System.currentTimeMillis() + delaySeconds * 1000 else 0L
        // commit() (synchronous) so killing the app can't skip the counter
        p.edit().putInt("fails", n).putLong("lock_until", until).commit()
        return n
    }

    fun recordSuccess() {
        p.edit().putInt("fails", 0).putLong("lock_until", 0L).commit()
    }
}
