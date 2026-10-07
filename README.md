# Secure Vault - offline Android password manager

A password manager that never touches the network. Everything is encrypted on the phone with a key
derived from your master password.

## Build and install

1. Install **Android Studio** (current stable) and open this folder (`SecureVault`). Let Gradle sync.
2. Plug in your phone (USB debugging on) and press **Run**, or use **Build > Build APK(s)** and copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone.
3. Requires Android 8.0 (API 26) or newer.

> This code was written without access to an Android SDK, so it has not been compiled or run yet.
> If Gradle reports a small compile error on first sync, it is a quick fix: send the message back and I will correct it.

## Security design

| Area | What it does |
|---|---|
| No network | The manifest declares **no INTERNET permission**, so Android blocks every network connection from the app. |
| Master password | Stretched with PBKDF2-HMAC-SHA256, 600,000 iterations, random 16-byte salt. Never stored. |
| Encryption | AES-256-GCM (authenticated: tampering is detected). Fresh random IV for every write. |
| Key hierarchy | Random 256-bit data key encrypts the vault; the master-password key only wraps that data key, so changing the master password is instant and safe. |
| Biometrics (optional) | Data key re-wrapped by a hardware-backed Android Keystore key that needs a fresh strong fingerprint/face check for each use, and is destroyed if new biometrics are enrolled. |
| Brute-force | After 5 wrong passwords the app enforces growing delays (30 s up to 1 h). |
| Screen protection | `FLAG_SECURE`: no screenshots, no screen recording, blank app-switcher preview. |
| Auto-lock | Locks when the screen turns off and after you leave the app (instantly / 30 s / 1 min / 5 min). |
| Clipboard | Copies are marked sensitive (Android 13+) and cleared after 30 s. |
| No cloud leaks | `allowBackup=false` plus data-extraction rules exclude all app data from Google backup and device transfer. |
| Atomic saves | Vault is written to a temp file, fsynced, then renamed, so a crash cannot corrupt it. |

Verify the no-internet claim yourself on the built APK:

```
aapt2 dump permissions app-debug.apk
```

You should see only `USE_BIOMETRIC`.

## Backups (important)

- There is **no master password recovery**. Forget it and the data is gone - by design.
- Settings > **Export encrypted backup** saves the encrypted vault file to a place you choose
  (USB drive, another phone, etc.). The file is useless without your master password.
- Losing, resetting or uninstalling the app deletes the vault, so keep a backup.
- Settings > **Import backup** restores one (replaces the current vault).

## Limits you should know about

- A phone that is already compromised (malware with root, malicious accessibility service) can still read what you display on screen.
- Android strings can't be wiped from RAM on demand; the key bytes are zeroed on lock, but decrypted text may stay in memory until garbage-collected. Locking and the screen-off lock minimise the window.
- Security depends on the master password: use a long passphrase (5+ random words).
- Brute-force delays are enforced by the app UI; someone who copies the vault file off a rooted phone can attack it offline, which is exactly what the 600k-iteration PBKDF2 and a strong passphrase defend against.

## Code map

- `Crypto.kt` - PBKDF2 + AES-GCM primitives
- `VaultStore.kt` - key hierarchy, encrypted file format, atomic save, backup import
- `BiometricHelper.kt` - Keystore-backed biometric unlock
- `SecurityPrefs.kt` - lockout counter, auto-lock setting, wrapped biometric key
- `VaultViewModel.kt` - app state and all actions
- `AuthScreens.kt`, `VaultScreens.kt`, `SettingsScreen.kt`, `MainActivity.kt` - Jetpack Compose UI
