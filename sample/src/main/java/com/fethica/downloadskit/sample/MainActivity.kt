package com.fethica.downloadskit.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fethica.downloadskit.sample.ui.SampleScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = (application as SampleApplication).sample
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                NotificationPermissionOnFirstStart()
                SampleScreen(sample)
            }
        }
    }

    /**
     * API 33+: the download service's progress notification needs POST_NOTIFICATIONS.
     * Asked once, on the first start, after a plain explanation; declining is fine, the
     * downloads still run, only the notification is hidden.
     */
    @Composable
    private fun NotificationPermissionOnFirstStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = remember { getSharedPreferences(SampleController.STATE_PREFS, Context.MODE_PRIVATE) }
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        var show by remember { mutableStateOf(!granted && !prefs.getBoolean(KEY_ASKED, false)) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            SampleLog.event("*", "notification_permission", "granted=$ok")
        }
        if (!show) return
        fun done() {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            show = false
        }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text("Download notifications") },
            text = {
                Text(
                    "Android shows a running download as a notification. Allow notifications to see " +
                        "the progress of the sample's fixture downloads there. Downloads work either way.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    done()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { done() }) { Text("Not now") } },
        )
    }

    private companion object {
        const val KEY_ASKED = "notification_permission_asked"
    }
}
