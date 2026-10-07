package com.amar.securevault

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.UUID

internal fun copyAndNotify(ctx: Context, label: String, text: String) {
    ClipboardHelper.copy(ctx, label, text)
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(ctx, "$label copied - clears in 30 s", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: VaultViewModel) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(vm.entries, query) {
        vm.entries
            .filter {
                query.isBlank() ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.username.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true)
            }
            .sortedBy { it.title.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Settings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { vm.lock() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock now")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.screen = Screen.Edit(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add entry")
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.entries.isEmpty()) "No passwords yet. Tap + to add your first one."
                        else "Nothing matches your search.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.title) },
                            supportingContent = { if (e.username.isNotEmpty()) Text(e.username) },
                            trailingContent = {
                                if (e.password.isNotEmpty()) {
                                    TextButton(onClick = { copyAndNotify(ctx, "Password", e.password) }) { Text("Copy") }
                                }
                            },
                            modifier = Modifier.clickable { vm.screen = Screen.Edit(e.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: VaultViewModel, id: String?) {
    val ctx = LocalContext.current
    val existing = remember(id) { vm.entries.firstOrNull { it.id == id } }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var showGenerator by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler { vm.screen = Screen.Home }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New entry" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (for example: Gmail)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username or email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    if (username.isNotEmpty()) {
                        TextButton(onClick = { copyAndNotify(ctx, "Username", username) }) { Text("Copy") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            PasswordField("Password", password, { password = it })
            StrengthBar(PasswordTools.strength(password))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showGenerator = true }) { Text("Generate") }
                OutlinedButton(
                    onClick = { copyAndNotify(ctx, "Password", password) },
                    enabled = password.isNotEmpty()
                ) { Text("Copy password") }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Website (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.saveEntry(
                        Entry(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            username = username.trim(),
                            password = password,
                            url = url.trim(),
                            notes = notes,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    vm.screen = Screen.Home
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }

    if (showGenerator) {
        GeneratorDialog(
            onUse = {
                password = it
                showGenerator = false
            },
            onDismiss = { showGenerator = false }
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("\"${existing.title}\" will be permanently removed from the vault.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntry(existing.id)
                    confirmDelete = false
                    vm.screen = Screen.Home
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun GeneratorDialog(onUse: (String) -> Unit, onDismiss: () -> Unit) {
    var length by remember { mutableFloatStateOf(20f) }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf("") }

    LaunchedEffect(length.toInt(), lower, upper, digits, symbols) {
        preview = PasswordTools.generate(length.toInt(), lower, upper, digits, symbols)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (preview.isEmpty()) "Pick at least one character type" else preview,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text("Length: ${length.toInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(value = length, onValueChange = { length = it }, valueRange = 8f..64f)
                CheckRow("Lowercase (a-z)", lower) { lower = it }
                CheckRow("Uppercase (A-Z)", upper) { upper = it }
                CheckRow("Digits (0-9)", digits) { digits = it }
                CheckRow("Symbols (!@#...)", symbols) { symbols = it }
                TextButton(onClick = {
                    preview = PasswordTools.generate(length.toInt(), lower, upper, digits, symbols)
                }) { Text("Regenerate") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUse(preview) }, enabled = preview.isNotEmpty()) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
