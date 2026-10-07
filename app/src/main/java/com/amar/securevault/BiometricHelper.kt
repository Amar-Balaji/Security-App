package com.amar.securevault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric unlock. The vault's data key is wrapped by an AES-256-GCM key that lives in
 * the Android Keystore (hardware-backed where available). That key can only be used right after a
 * successful STRONG biometric check (per-use auth) and is destroyed if new fingerprints/faces are
 * enrolled on the phone.
 */
object BiometricHelper {
    private const val ALIAS = "securevault_bio_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun deleteKey() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // nothing to delete
        }
    }

    @Suppress("DEPRECATION")
    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun encryptCipher(): Cipher {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return c
    }

    private fun decryptCipher(iv: ByteArray): Cipher {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return c
    }

    /** Turn biometric unlock on (vault must currently be unlocked). */
    fun enable(activity: FragmentActivity, vm: VaultViewModel) {
        val cipher = try {
            encryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            try {
                encryptCipher()
            } catch (e2: Exception) {
                vm.message = "Could not set up biometric unlock."
                return
            }
        } catch (e: Exception) {
            vm.message = "Could not set up biometric unlock."
            return
        }
        authenticate(activity, "Enable biometric unlock", cipher, vm) { vm.completeBiometricEnable(it) }
    }

    /** Ask for a fingerprint/face and unlock the vault. */
    fun unlock(activity: FragmentActivity, vm: VaultViewModel) {
        val ivText = vm.prefs.bioIv ?: return
        val cipher = try {
            decryptCipher(Base64.decode(ivText, Base64.NO_WRAP))
        } catch (e: KeyPermanentlyInvalidatedException) {
            vm.disableBiometric()
            vm.message = "Biometrics changed on this phone. Unlock with your master password, then re-enable."
            return
        } catch (e: Exception) {
            vm.message = "Biometric unlock unavailable. Use your master password."
            return
        }
        authenticate(activity, "Unlock Secure Vault", cipher, vm) { vm.completeBiometricUnlock(it) }
    }

    private fun authenticate(
        activity: FragmentActivity,
        title: String,
        cipher: Cipher,
        vm: VaultViewModel,
        onSuccess: (Cipher) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val quiet = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (!quiet) vm.message = errString.toString()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use master password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
