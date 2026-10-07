package com.amar.securevault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: VaultViewModel, activity: FragmentActivity) {
    val ctx = LocalContext.current
    var showChangePassword by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val bioAvailable = remember { BiometricHelper.isAvailable(ctx) }

    BackHandler { vm.screen = Screen.Home }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        vm.pickerInFlight = false
        if (uri != null) vm.exportBackup(ctx.contentResolver, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        vm.pickerInFlight = false
        if (uri != null) pendingImport = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("Unlock")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric unlock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (bioAvailable) "Fingerprint or face (strong biometrics only). Your master password always works."
                        else "No strong biometric is enrolled on this phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = vm.bioEnabled,
                    enabled = bioAvailable,
                    onCheckedChange = { on ->
                        if (on) BiometricHelper.enable(activity, vm) else vm.disableBiometric()
                    }
                )
            }

            Text("Auto-lock after leaving the app", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Instantly", 30 to "30 s", 60 to "1 min", 300 to "5 min").forEach { (sec, label) ->
                    FilterChip(
                        selected = vm.autoLock == sec,
                        onClick = { vm.updateAutoLock(sec) },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "The vault also locks whenever the screen turns off.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()
            SectionTitle("Master password")
            OutlinedButton(onClick = { showChangePassword = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Change master password")
            }

            HorizontalDivider()
            SectionTitle("Encrypted backup")
            Text(
                "A backup is the encrypted vault file. It is useless without your master password, " +
                    "so you can keep it on a USB drive or another device. Without a backup, losing or " +
                    "resetting this phone loses your passwords.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    vm.pickerInFlight = true
                    exportLauncher.launch("securevault-backup.svault")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export encrypted backup") }
            OutlinedButton(
                onClick = {
                    vm.pickerInFlight = true
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import backup (replaces this vault)") }

            HorizontalDivider()
            SectionTitle("Session")
            OutlinedButton(onClick = { vm.lock() }, modifier = Modifier.fillMaxWidth()) { Text("Lock now") }
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(vm = vm, onDismiss = { showChangePassword = false })
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Replace this vault?") },
            text = {
                Text(
                    "All passwords currently in this app will be replaced by the backup. " +
                        "You will unlock it with the master password the backup was made with. " +
                        "Export a backup of the current vault first if you are unsure."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importBackup(ctx.contentResolver, uri)
                    pendingImport = null
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ChangePasswordDialog(vm: VaultViewModel, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    val valid = current.isNotEmpty() && new1.length >= 10 && new1 == new2

    AlertDialog(
        onDismissRequest = { if (!vm.busy) onDismiss() },
        title = { Text("Change master password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField("Current password", current, { current = it }, enabled = !vm.busy)
                PasswordField("New password (10+ characters)", new1, { new1 = it }, enabled = !vm.busy)
                StrengthBar(PasswordTools.strength(new1))
                PasswordField("Confirm new password", new2, { new2 = it }, enabled = !vm.busy)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !vm.busy,
                onClick = { vm.changePassword(current, new1) { ok -> if (ok) onDismiss() } }
            ) { Text(if (vm.busy) "Working..." else "Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.busy) { Text("Cancel") } }
    )
}
