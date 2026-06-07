package com.messenger.crisix.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.messenger.crisix.ui.theme.NavyAccent
import com.messenger.crisix.ui.theme.NavyWarning
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.util.PermissionManager

/**
 * Permission-Setup-Seite: Fragt alle Runtime-Permissions auf einmal an,
 * die Crisix benötigt (BLE, RECORD_AUDIO, CAMERA).
 *
 * Der Benutzer sieht eine Übersicht, welche Permissions benötigt werden,
 * und kann mit einem Klick alle anfordern.
 */
@Composable
fun PermissionSetupScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current

    // Status der einzelnen Permission-Gruppen
    var bleGranted by remember { mutableStateOf(PermissionManager.hasBlePermissions(context)) }
    var audioGranted by remember { mutableStateOf(PermissionManager.hasAudioPermission(context)) }
    var cameraGranted by remember { mutableStateOf(PermissionManager.hasCameraPermission(context)) }
    var notificationGranted by remember { mutableStateOf(PermissionManager.hasNotificationPermission(context)) }
    var nanGranted by remember { mutableStateOf(PermissionManager.hasWifiAwarePermissions(context)) }
    val nanSupported = remember { PermissionManager.isNanSupported(context) }
    var smsGranted by remember { mutableStateOf(PermissionManager.hasSmsPermissions(context)) }

    // Alle Permissions wurden erteilt?
    val allGranted = bleGranted && audioGranted && cameraGranted && notificationGranted && (nanGranted || !nanSupported) && smsGranted

    // Sequentielles Permission-Request-Tracking (-1 = idle, 0–5 = active step)
    var permissionStep by remember { mutableStateOf(-1) }

    // Launcher für BLE-Permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
    val bleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        bleGranted = permissions.values.all { it }
        permissionStep = 1
    }

    // Launcher für RECORD_AUDIO
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
        permissionStep = 2
    }

    // Launcher für CAMERA
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        permissionStep = 3
    }

    // Launcher für POST_NOTIFICATIONS (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
        permissionStep = 4
    }

    // Launcher für WiFi Aware (NAN) Permissions (Android 12+)
    val nanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        nanGranted = permissions.values.all { it }
        permissionStep = 5
    }

    // Launcher für SMS Permissions (SEND_SMS + RECEIVE_SMS)
    val smsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        smsGranted = permissions.values.all { it }
        permissionStep = 6
    }

    // Sequentiell Permissions anfordern via LaunchedEffect
    LaunchedEffect(permissionStep) {
        when (permissionStep) {
            0 -> {
                if (!bleGranted) {
                    bleLauncher.launch(PermissionManager.blePermissions())
                } else {
                    permissionStep = 1
                }
            }
            1 -> {
                if (!audioGranted) {
                    audioLauncher.launch(PermissionManager.audioPermission())
                } else {
                    permissionStep = 2
                }
            }
            2 -> {
                if (!cameraGranted) {
                    cameraLauncher.launch(PermissionManager.cameraPermission())
                } else {
                    permissionStep = 3
                }
            }
            3 -> {
                if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(PermissionManager.notificationPermission())
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        notificationGranted = true
                    }
                    permissionStep = 4
                }
            }
            4 -> {
                if (!nanSupported || nanGranted) {
                    nanGranted = true
                    permissionStep = 5
                } else {
                    val nanPerms = PermissionManager.wifiAwarePermissions()
                    if (nanPerms.isNotEmpty()) {
                        nanLauncher.launch(nanPerms)
                    } else {
                        nanGranted = true
                        permissionStep = 5
                    }
                }
            }
            5 -> {
                val smsPerms = PermissionManager.smsPermissions()
                if (!smsGranted && smsPerms.isNotEmpty()) {
                    smsLauncher.launch(smsPerms)
                } else {
                    if (smsPerms.isEmpty()) {
                        smsGranted = true
                    }
                    permissionStep = -1
                }
            }
            else -> { /* idle or done */ }
        }
    }

    // Alle Permissions sequentiell anfordern
    fun requestAllPermissions() {
        if (!PermissionManager.canRequestRuntimePermissions()) {
            bleGranted = true
            audioGranted = true
            cameraGranted = true
            notificationGranted = true
            nanGranted = true
            smsGranted = true
            return
        }
        permissionStep = 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.permission_setup_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.permission_setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Permission-Liste
        PermissionItem(
            icon = R.drawable.ic_bluetooth,
            label = stringResource(R.string.permission_ble_label),
            description = stringResource(R.string.permission_ble_desc),
            granted = bleGranted
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionItem(
            icon = R.drawable.ic_mic,
            label = stringResource(R.string.permission_audio_label),
            description = stringResource(R.string.permission_audio_desc),
            granted = audioGranted
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionItem(
            icon = R.drawable.ic_qr_code,
            label = stringResource(R.string.permission_camera_label),
            description = stringResource(R.string.permission_camera_desc),
            granted = cameraGranted
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionItem(
            icon = R.drawable.ic_notifications,
            label = stringResource(R.string.permission_notification_label),
            description = stringResource(R.string.permission_notification_desc),
            granted = notificationGranted
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (nanSupported) {
            PermissionItem(
                icon = R.drawable.ic_wifi,
                label = stringResource(R.string.permission_nan_label),
                description = stringResource(R.string.permission_nan_desc),
                granted = nanGranted
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        PermissionItem(
            icon = R.drawable.ic_sms,
            label = stringResource(R.string.permission_sms_label),
            description = stringResource(R.string.permission_sms_desc),
            granted = smsGranted
        )

        Spacer(modifier = Modifier.weight(1f))

        // Hinweis, falls Permissions verweigert wurden
        if (!allGranted && (bleGranted || audioGranted || cameraGranted)) {
            Text(
                text = stringResource(R.string.permission_setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                if (allGranted) {
                    onComplete()
                } else {
                    requestAllPermissions()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(
                text = if (allGranted)
                    stringResource(R.string.permission_setup_continue)
                else
                    stringResource(R.string.permission_setup_grant),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionItem(
    icon: Int,
    label: String,
    description: String,
    granted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (granted) NavyAccent else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Status-Icon: ✓ oder ⚠
        Text(
            text = if (granted) "✓" else "⚠",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (granted) NavyAccent else NavyWarning
        )
    }
}
