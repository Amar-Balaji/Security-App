package com.amar.securevault

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import javax.crypto.Cipher

sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: String?) : Screen
    data object Settings : Screen
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val store = VaultStore(app)
    val prefs = SecurityPrefs(app)

    // One thread for all disk writes keeps saves strictly in order.
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    var hasVault by mutableStateOf(store.exists())
        private set
    var unlocked by mutableStateOf(false)
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var bioEnabled by mutableStateOf(prefs.bioConfigured)
        private set
    var autoLock by mutableIntStateOf(prefs.autoLockSeconds)
        private set
    var unlockError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var screen by mutableStateOf<Screen>(Screen.Home)

    /** True while a system file picker is open, so auto-lock doesn't fire mid-export/import. */
    var pickerInFlight = false
    private var stoppedAt = 0L

    // ---------- create / unlock / lock ----------

    fun createVault(password: String) {
        viewModelScope.launch {
            busy = true
            val chars = password.toCharArray()
            try {
                withContext(Dispatchers.Default) { store.create(chars) }
                entries = emptyList()
                hasVault = true
                unlocked = true
                screen = Screen.Home
            } catch (e: Exception) {
                message = "Could not create the vault."
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    fun unlock(password: String) {
        if (System.currentTimeMillis() < prefs.lockUntil) return
        viewModelScope.launch {
            busy = true
            unlockError = null
            val chars = password.toCharArray()
            try {
                val result = withContext(Dispatchers.Default) { runCatching { store.unlock(chars) } }
                val list = result.getOrNull()
                when {
                    result.isFailure -> unlockError = "The vault file is damaged or unreadable."
                    list == null -> {
                        val n = prefs.recordFailure()
                        unlockError = if (n >= 5) "Wrong password. Too many attempts." else "Wrong password."
                    }
                    else -> {
                        prefs.recordSuccess()
                        entries = list
                        unlocked = true
                        screen = Screen.Home
                    }
                }
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    fun lock() {
        store.lock()
        entries = emptyList()
        unlocked = false
        screen = Screen.Home
    }

    // ---------- entries ----------

    fun saveEntry(e: Entry) {
        val updated = if (entries.any { it.id == e.id }) {
            entries.map { if (it.id == e.id) e else it }
        } else {
            entries + e
        }
        entries = updated
        persist(updated)
    }

    fun deleteEntry(id: String) {
        val updated = entries.filterNot { it.id == id }
        entries = updated
        persist(updated)
    }

    private fun persist(list: List<Entry>) {
        val key = store.keyCopy() ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                store.save(list, key)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Could not save changes to disk!" }
            } finally {
                key.fill(0)
            }
        }
    }

    // ---------- master password ----------

    fun changePassword(oldPw: String, newPw: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            busy = true
            val o = oldPw.toCharArray()
            val n = newPw.toCharArray()
            try {
                val ok = withContext(ioDispatcher) { store.changePassword(o, n) }
                message = if (ok) "Master password changed." else "Current password is wrong."
                onDone(ok)
            } catch (e: Exception) {
                message = "Could not change the master password."
                onDone(false)
            } finally {
                o.fill('\u0000')
                n.fill('\u0000')
                busy = false
            }
        }
    }

    // ---------- auto-lock ----------

    fun updateAutoLock(seconds: Int) {
        prefs.autoLockSeconds = seconds
        autoLock = seconds
    }

    fun onAppStopped() {
        if (pickerInFlight) return
        stoppedAt = SystemClock.elapsedRealtime()
        if (unlocked && autoLock == 0) lock()
    }

    fun onAppStarted() {
        if (pickerInFlight) return
        if (unlocked && autoLock > 0 && stoppedAt > 0 &&
            SystemClock.elapsedRealtime() - stoppedAt > autoLock * 1000L
        ) {
            lock()
        }
        stoppedAt = 0
    }

    // ---------- biometrics ----------

    fun completeBiometricEnable(cipher: Cipher) {
        val key = store.keyCopy() ?: return
        try {
            val wrapped = cipher.doFinal(key)
            prefs.bioIv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            prefs.bioBlob = Base64.encodeToString(wrapped, Base64.NO_WRAP)
            bioEnabled = true
            message = "Biometric unlock enabled."
        } catch (e: Exception) {
            message = "Could not enable biometric unlock."
        } finally {
            key.fill(0)
        }
    }

    fun completeBiometricUnlock(cipher: Cipher) {
        val blob = prefs.bioBlob ?: return
        val key = try {
            cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP))
        } catch (e: Exception) {
            disableBiometric()
            message = "Biometric unlock failed. Use your master password."
            return
        }
        val list = try {
            store.unlockWithKey(key)
        } catch (e: Exception) {
            null
        }
        key.fill(0)
        if (list == null) {
            disableBiometric()
            message = "Biometric unlock failed. Use your master password."
            return
        }
        prefs.recordSuccess()
        unlockError = null
        entries = list
        unlocked = true
        screen = Screen.Home
    }

    fun disableBiometric() {
        prefs.clearBio()
        BiometricHelper.deleteKey()
        bioEnabled = false
    }

    // ---------- encrypted backup ----------

    fun exportBackup(cr: ContentResolver, uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            val msg = try {
                val bytes = store.readRaw()
                val out = cr.openOutputStream(uri, "wt") ?: throw IllegalStateException("No stream")
                out.use { it.write(bytes) }
                "Encrypted backup saved."
            } catch (e: Exception) {
                "Export failed."
            }
            withContext(Dispatchers.Main) { message = msg }
        }
    }

    fun importBackup(cr: ContentResolver, uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            val msg = try {
                val input = cr.openInputStream(uri) ?: throw IllegalStateException("No stream")
                val bytes = input.use { readLimited(it, VaultStore.MAX_FILE_BYTES) }
                store.importBackup(bytes)
                prefs.clearBio()
                BiometricHelper.deleteKey()
                prefs.recordSuccess()
                withContext(Dispatchers.Main) {
                    bioEnabled = false
                    entries = emptyList()
                    unlocked = false
                    hasVault = true
                    screen = Screen.Home
                }
                "Backup imported. Unlock with that backup's master password."
            } catch (e: Exception) {
                "That file is not a valid Secure Vault backup."
            }
            withContext(Dispatchers.Main) { message = msg }
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= max) { "File too large" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun onCleared() {
        store.lock()
        super.onCleared()
    }
}
