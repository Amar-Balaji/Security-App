package com.amar.securevault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.lock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Blocks screenshots, screen recording and the app-switcher thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val activity: FragmentActivity = this
        setContent {
            SecureVaultTheme { App(vm, activity) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppStarted()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onAppStopped()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}

@Composable
fun SecureVaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: VaultViewModel, activity: FragmentActivity) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !vm.hasVault -> SetupScreen(vm)
            !vm.unlocked -> UnlockScreen(vm, activity)
            else -> when (val s = vm.screen) {
                Screen.Home -> HomeScreen(vm)
                is Screen.Edit -> EditScreen(vm, s.id)
                Screen.Settings -> SettingsScreen(vm, activity)
            }
        }
    }
}
