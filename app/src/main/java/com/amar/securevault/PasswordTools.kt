package com.amar.securevault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.security.SecureRandom
import kotlin.math.ln

data class Strength(val bits: Double, val label: String)

object PasswordTools {
    private val rng = SecureRandom()
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/"

    /** Uses SecureRandom with rejection-free uniform selection and guarantees one char per chosen class. */
    fun generate(length: Int, lower: Boolean, upper: Boolean, digits: Boolean, symbols: Boolean): String {
        val pools = listOfNotNull(
            LOWER.takeIf { lower },
            UPPER.takeIf { upper },
            DIGITS.takeIf { digits },
            SYMBOLS.takeIf { symbols }
        )
        if (pools.isEmpty() || length < pools.size) return ""
        val all = pools.joinToString("")
        val chars = CharArray(length) { all[rng.nextInt(all.length)] }
        val positions = (0 until length).toMutableList()
        positions.shuffle(rng)
        pools.forEachIndexed { i, pool -> chars[positions[i]] = pool[rng.nextInt(pool.length)] }
        return String(chars)
    }

    /** Rough entropy estimate; repeated characters count for much less. Only a guide. */
    fun strength(p: String): Strength {
        if (p.isEmpty()) return Strength(0.0, "")
        var pool = 0
        if (p.any { it.isLowerCase() }) pool += 26
        if (p.any { it.isUpperCase() }) pool += 26
        if (p.any { it.isDigit() }) pool += 10
        if (p.any { !it.isLetterOrDigit() }) pool += 33
        if (pool == 0) pool = 26
        val distinct = p.toSet().size
        val effectiveLength = distinct + (p.length - distinct) * 0.25
        val bits = effectiveLength * ln(pool.toDouble()) / ln(2.0)
        val label = when {
            bits < 40 -> "Weak"
            bits < 60 -> "Fair"
            bits < 80 -> "Strong"
            else -> "Excellent"
        }
        return Strength(bits, label)
    }
}

object ClipboardHelper {
    private const val CLEAR_AFTER_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Copies as "sensitive" (hidden from clipboard previews on Android 13+) and auto-clears after 30 s. */
    fun copy(context: Context, label: String, text: String) {
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { clearIfStillOurs(cm, text) }
        pending = r
        handler.postDelayed(r, CLEAR_AFTER_MS)
    }

    private fun clearIfStillOurs(cm: ClipboardManager, text: String) {
        val current = try {
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            null
        }
        // Android 10+ hides the clipboard from background apps (null) - clear in that case too.
        if (current == null || current == text) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
