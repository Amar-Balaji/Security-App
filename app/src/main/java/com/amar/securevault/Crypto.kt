package com.amar.securevault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * All cryptography uses only the standard Android/Java providers:
 *  - PBKDF2-HMAC-SHA256 (600k iterations) to stretch the master password
 *  - AES-256-GCM (authenticated encryption) with a fresh random 96-bit IV per encryption
 */
object Crypto {
    const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns IV (12 bytes) || ciphertext || GCM tag. */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    /** Throws GeneralSecurityException (AEADBadTagException) on a wrong key or tampered data. */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + GCM_TAG_BITS / 8) throw GeneralSecurityException("Blob too short")
        val iv = blob.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}
