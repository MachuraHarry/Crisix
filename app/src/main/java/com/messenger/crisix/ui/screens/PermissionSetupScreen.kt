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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    // Alle Permissions wurden erteilt?
    val allGranted = bleGranted && audioGranted && cameraGranted && notificationGranted

    // Launcher für BLE-Permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
    val bleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        bleGranted = permissions.values.all { it }
    }

    // Launcher für RECORD_AUDIO
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
    }

    // Launcher für CAMERA
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
    }

    // Launcher für POST_NOTIFICATIONS (Android 13+)
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    // Alle Permissions auf einmal anfordern
    fun requestAllPermissions() {
        if (!PermissionManager.canRequestRuntimePermissions()) {
            // API < 6 → keine Runtime-Permissions nötig
            bleGranted = true
            audioGranted = true
            cameraGranted = true
            notificationGranted = true
            return
        }

        // BLE-Permissions anfordern (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
        if (!bleGranted) {
            bleLauncher.launch(PermissionManager.blePermissions())
        }

        // RECORD_AUDIO anfordern
        if (!audioGranted) {
            audioLauncher.launch(PermissionManager.audioPermission())
        }

        // CAMERA anfordern
        if (!cameraGranted) {
            cameraLauncher.launch(PermissionManager.cameraPermission())
        }

        // POST_NOTIFICATIONS anfordern (nur Android 13+)
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(PermissionManager.notificationPermission())
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = true
        }
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
            tint = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
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
            color = if (granted) Color(0xFF4CAF50) else Color(0xFFFFA000)
        )
    }
}
