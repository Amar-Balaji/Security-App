package com.amar.securevault

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException

/**
 * Key hierarchy:
 *   master password --PBKDF2--> KEK --wraps--> random 256-bit DEK --encrypts--> vault data
 *
 * Changing the master password only re-wraps the DEK. The vault lives in one file
 * (filesDir/vault.dat, app-private, excluded from every backup) written atomically.
 */
class VaultStore(context: Context) {
    private val file = File(context.filesDir, "vault.dat")
    private val tmpFile = File(context.filesDir, "vault.tmp")
    private val keyLock = Any()
    private val fileLock = Any()
    private var dek: ByteArray? = null

    companion object {
        private val AAD_DEK = "securevault:v1:dek".toByteArray()
        private val AAD_DATA = "securevault:v1:data".toByteArray()
        private const val SALT_BYTES = 16
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }

    fun exists(): Boolean = file.exists()

    /** Creates a brand-new vault and leaves it unlocked. */
    fun create(password: CharArray) {
        val salt = Crypto.randomBytes(SALT_BYTES)
        val kek = Crypto.deriveKey(password, salt, Crypto.PBKDF2_ITERATIONS)
        val newDek = Crypto.randomBytes(32)
        try {
            val header = JSONObject()
                .put("v", 1)
                .put("iter", Crypto.PBKDF2_ITERATIONS)
                .put("salt", enc(salt))
                .put("dek", enc(Crypto.encrypt(kek, newDek, AAD_DEK)))
                .put("data", enc(Crypto.encrypt(newDek, EntryJson.toBytes(emptyList()), AAD_DATA)))
            synchronized(fileLock) { atomicWrite(header) }
            setKey(newDek)
        } finally {
            kek.fill(0)
            newDek.fill(0)
        }
    }

    /** Returns the entries, or null if the password is wrong. Throws if the file is damaged. */
    fun unlock(password: CharArray): List<Entry>? {
        val h = synchronized(fileLock) { readFile() }
        val kek = Crypto.deriveKey(password, dec(h.getString("salt")), h.getInt("iter"))
        try {
            val d = try {
                Crypto.decrypt(kek, dec(h.getString("dek")), AAD_DEK)
            } catch (e: GeneralSecurityException) {
                return null
            }
            try {
                return openData(h, d)
            } finally {
                d.fill(0)
            }
        } finally {
            kek.fill(0)
        }
    }

    /** Unlock with an already-unwrapped data key (biometric path). Null if the key doesn't fit. */
    fun unlockWithKey(d: ByteArray): List<Entry>? {
        val h = synchronized(fileLock) { readFile() }
        return try {
            openData(h, d)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun openData(h: JSONObject, d: ByteArray): List<Entry> {
        val plain = Crypto.decrypt(d, dec(h.getString("data")), AAD_DATA)
        try {
            val list = EntryJson.fromBytes(plain)
            setKey(d)
            return list
        } finally {
            plain.fill(0)
        }
    }

    /** Encrypts and writes the entries using the supplied key copy (see keyCopy()). */
    fun save(list: List<Entry>, key: ByteArray) {
        val plain = EntryJson.toBytes(list)
        try {
            val blob = Crypto.encrypt(key, plain, AAD_DATA)
            synchronized(fileLock) {
                val h = readFile()
                h.put("data", enc(blob))
                atomicWrite(h)
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Re-wraps the data key under a new master password. Returns false if [oldPw] is wrong. */
    fun changePassword(oldPw: CharArray, newPw: CharArray): Boolean {
        val key = keyCopy() ?: return false
        try {
            val h0 = synchronized(fileLock) { readFile() }
            val oldKek = Crypto.deriveKey(oldPw, dec(h0.getString("salt")), h0.getInt("iter"))
            try {
                Crypto.decrypt(oldKek, dec(h0.getString("dek")), AAD_DEK).fill(0)
            } catch (e: GeneralSecurityException) {
                return false
            } finally {
                oldKek.fill(0)
            }
            val salt = Crypto.randomBytes(SALT_BYTES)
            val newKek = Crypto.deriveKey(newPw, salt, Crypto.PBKDF2_ITERATIONS)
            try {
                val wrapped = Crypto.encrypt(newKek, key, AAD_DEK)
                synchronized(fileLock) {
                    val h = readFile()
                    h.put("salt", enc(salt)).put("iter", Crypto.PBKDF2_ITERATIONS).put("dek", enc(wrapped))
                    atomicWrite(h)
                }
            } finally {
                newKek.fill(0)
            }
            return true
        } finally {
            key.fill(0)
        }
    }

    /** The raw encrypted file; safe to hand to the user as a backup. */
    fun readRaw(): ByteArray = synchronized(fileLock) { file.readBytes() }

    /** Replaces the vault with an encrypted backup after validating its structure. */
    fun importBackup(bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES) { "File too large" }
        val h = JSONObject(String(bytes, Charsets.UTF_8))
        require(h.getInt("v") == 1) { "Unsupported version" }
        require(h.getInt("iter") in 100_000..5_000_000) { "Bad iteration count" }
        require(dec(h.getString("salt")).size == SALT_BYTES) { "Bad salt" }
        dec(h.getString("dek"))
        dec(h.getString("data"))
        synchronized(fileLock) { atomicWrite(h) }
        lock()
    }

    fun keyCopy(): ByteArray? = synchronized(keyLock) { dek?.copyOf() }

    private fun setKey(d: ByteArray) {
        synchronized(keyLock) {
            dek?.fill(0)
            dek = d.copyOf()
        }
    }

    fun lock() {
        synchronized(keyLock) {
            dek?.fill(0)
            dek = null
        }
    }

    private fun readFile(): JSONObject {
        require(file.length() <= MAX_FILE_BYTES) { "Vault file too large" }
        return JSONObject(String(file.readBytes(), Charsets.UTF_8))
    }

    private fun atomicWrite(h: JSONObject) {
        FileOutputStream(tmpFile).use { out ->
            out.write(h.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
