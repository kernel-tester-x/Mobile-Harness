package com.jarves.mh.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionsSetupScreen(
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val allFilesGranted = remember(tick) {
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    }
    val writeSettingsGranted = remember(tick) {
        Settings.System.canWrite(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("MH Local Permissions", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Grant these permissions once so Local AI, Downloads, Accessibility, and background tools work offline without interruptions.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermCard(
                title = "All Files Access",
                desc = if (allFilesGranted) "Granted — models & tool backups ready" else "Needed to read/save AI models & backups in Downloads",
                done = allFilesGranted,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                    tick++
                },
            )
            PermCard(
                title = "Notifications",
                desc = "Keeps Local AI engine & background downloads alive",
                done = false,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }
                },
            )
            PermCard(
                title = "Accessibility Screen Control",
                desc = "Enables whole-phone automation (127.0.0.1:8081)",
                done = false,
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                },
            )
            PermCard(
                title = "Modify System Settings",
                desc = if (writeSettingsGranted) "Granted" else "Optional: lets automation adjust brightness/rotation",
                done = writeSettingsGranted,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                    tick++
                },
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Continue to MH Local", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PermCard(title: String, desc: String, done: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, fontSize = 11.sp, color = if (done) Color(0xFF81C995) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(50)) {
                Text(if (done) "Granted" else "Allow", fontSize = 12.sp)
            }
        }
    }
}
