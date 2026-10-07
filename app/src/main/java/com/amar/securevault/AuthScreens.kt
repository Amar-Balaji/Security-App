package com.amar.securevault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = keyboardActions,
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
        }
    )
}

@Composable
fun StrengthBar(s: Strength) {
    if (s.label.isEmpty()) return
    val color = when (s.label) {
        "Weak" -> MaterialTheme.colorScheme.error
        "Fair" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { (s.bits / 100.0).coerceIn(0.0, 1.0).toFloat() },
            color = color,
            modifier = Modifier.fillMaxWidth()
        )
        Text("${s.label} (about ${s.bits.toInt()} bits)", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SetupScreen(vm: VaultViewModel) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    val valid = pw.length >= 10 && pw == pw2 && understood && !vm.busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Create your vault", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your master password encrypts everything on this phone. It is never stored and the app has no internet access. " +
                "Use a long passphrase (for example 5 or more random words).",
            style = MaterialTheme.typography.bodyMedium
        )
        PasswordField("Master password", pw, { pw = it }, enabled = !vm.busy)
        StrengthBar(PasswordTools.strength(pw))
        if (pw.isNotEmpty() && pw.length < 10) {
            Text("At least 10 characters.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        PasswordField("Confirm master password", pw2, { pw2 = it }, enabled = !vm.busy, imeAction = ImeAction.Done)
        if (pw2.isNotEmpty() && pw != pw2) {
            Text("Passwords do not match.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = understood, onCheckedChange = { understood = it })
            Text(
                "I understand a forgotten master password cannot be recovered by anyone.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = { vm.createVault(pw) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.busy) "Creating..." else "Create vault") }
    }
}

@Composable
fun UnlockScreen(vm: VaultViewModel, activity: FragmentActivity) {
    var pw by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        if (vm.bioEnabled) BiometricHelper.unlock(activity, vm)
    }

    val remaining = ((vm.prefs.lockUntil - now + 999) / 1000).coerceAtLeast(0)
    val locked = remaining > 0
    val submit = {
        if (pw.isNotEmpty() && !locked && !vm.busy) {
            vm.unlock(pw)
            pw = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Secure Vault", style = MaterialTheme.typography.headlineMedium)
        PasswordField(
            label = "Master password",
            value = pw,
            onValueChange = { pw = it },
            enabled = !vm.busy && !locked,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        vm.unlockError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (locked) {
            Text(
                "Too many wrong attempts. Try again in ${remaining}s.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(
            onClick = { submit() },
            enabled = pw.isNotEmpty() && !locked && !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.busy) "Unlocking..." else "Unlock") }
        if (vm.bioEnabled) {
            OutlinedButton(
                onClick = { BiometricHelper.unlock(activity, vm) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Use biometrics") }
        }
    }
}
