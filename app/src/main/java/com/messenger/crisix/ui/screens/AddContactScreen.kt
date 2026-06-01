package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportManager
import kotlinx.coroutines.launch

/**
 * Bildschirm zum Hinzufügen neuer Kontakte.
 *
 * Bietet drei Wege:
 * 1. QR-Code scannen (primär)
 * 2. Geheimen Raum beitreten (global über DHT)
 * 3. Manuelle ID-Eingabe (Fallback)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    transportManager: TransportManager?,
    onBackClick: () -> Unit,
    onContactAdded: (String, String) -> Unit = { _, _ -> },
    onOpenQrScanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSecretRoomDialog by remember { mutableStateOf(false) }
    var showManualIdDialog by remember { mutableStateOf(false) }
    var showIpDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.add_contact_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === Weg 1: QR-Code scannen (primär) ===
            ContactMethodCard(
                icon = R.drawable.ic_qr_code,
                title = stringResource(R.string.add_contact_qr_title),
                description = stringResource(R.string.add_contact_qr_description),
                isPrimary = true,
                onClick = onOpenQrScanner
            )

            // === Weg 2: Geheimer Raum ===
            ContactMethodCard(
                icon = R.drawable.ic_network,
                title = stringResource(R.string.add_contact_secret_room_title),
                description = stringResource(R.string.add_contact_secret_room_description),
                isPrimary = false,
                onClick = { showSecretRoomDialog = true }
            )

            // === Weg 3: Manuelle ID ===
            ContactMethodCard(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.add_contact_short_id_title),
                description = stringResource(R.string.add_contact_short_id_description),
                isPrimary = false,
                onClick = { showManualIdDialog = true }
            )

            // === Weg 4: IP:Port (für Experten) ===
            ContactMethodCard(
                icon = R.drawable.ic_wifi,
                title = stringResource(R.string.add_contact_ip_title),
                description = stringResource(R.string.add_contact_ip_description),
                isPrimary = false,
                onClick = { showIpDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === Hinweise ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.add_contact_hints_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_contact_hint_qr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.add_contact_hint_secret_room),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.add_contact_hint_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // === Geheimer Raum Dialog ===
    if (showSecretRoomDialog) {
        SecretRoomDialog(
            onJoin = { roomName ->
                showSecretRoomDialog = false
                // Raum-Namen hashen und in DHT suchen
                scope.launch {
                    joinSecretRoom(roomName, transportManager)
                }
            },
            onDismiss = { showSecretRoomDialog = false }
        )
    }

    // === Manuelle ID Dialog ===
    if (showManualIdDialog) {
        ManualIdDialog(
            onConnect = { shortId ->
                showManualIdDialog = false
                // Kurz-ID in der DHT suchen
                scope.launch {
                    connectViaShortId(shortId, transportManager)
                }
            },
            onDismiss = { showManualIdDialog = false }
        )
    }

    // === IP:Port Dialog ===
    if (showIpDialog) {
        IpAddressDialog(
            onConnect = { ip, port ->
                showIpDialog = false
                scope.launch {
                    transportManager?.let { mgr ->
                        mgr.connectToPeer("$ip:$port", null)
                    }
                }
            },
            onDismiss = { showIpDialog = false }
        )
    }
}

/**
 * Karte für eine Kontaktmethode.
 */
@Composable
private fun ContactMethodCard(
    icon: Int,
    title: String,
    description: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPrimary) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = title,
                    modifier = Modifier.size(24.dp),
                    tint = if (isPrimary) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isPrimary) {
                Text(
                    text = stringResource(R.string.add_contact_badge_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================
// Dialoge
// ============================================================

/**
 * Dialog zum Beitreten eines geheimen Raums.
 */
@Composable
private fun SecretRoomDialog(
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var roomName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.add_contact_secret_room_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_contact_secret_room_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                }

                OutlinedTextField(
                    value = roomName,
                    onValueChange = {
                        roomName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_contact_secret_room_label)) },
                    placeholder = { Text(stringResource(R.string.add_contact_secret_room_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.add_contact_secret_room_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (roomName.isBlank()) {
                        error = context.getString(R.string.add_contact_secret_room_error_empty)
                    } else {
                        onJoin(roomName.trim())
                    }
                }
            ) {
                Text(stringResource(R.string.action_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Dialog für manuelle ID-Eingabe (8-stellige Kurz-ID).
 */
@Composable
private fun ManualIdDialog(
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var shortId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.add_contact_short_id_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_contact_short_id_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                }

                OutlinedTextField(
                    value = shortId,
                    onValueChange = {
                        shortId = it.take(8)
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_contact_short_id_label)) },
                    placeholder = { Text(stringResource(R.string.add_contact_short_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.add_contact_short_id_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (shortId.length < 4) {
                        error = context.getString(R.string.add_contact_short_id_error_too_short)
                    } else {
                        onConnect(shortId.trim())
                    }
                }
            ) {
                Text(stringResource(R.string.action_search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Dialog für IP:Port-Eingabe.
 */
@Composable
private fun IpAddressDialog(
    onConnect: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.add_contact_ip_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_contact_ip_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                }

                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text(stringResource(R.string.add_contact_ip_label)) },
                    placeholder = { Text(stringResource(R.string.add_contact_ip_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.add_contact_port_label)) },
                    placeholder = { Text(stringResource(R.string.add_contact_port_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNum = port.toIntOrNull()
                    if (ipAddress.isBlank()) {
                        error = context.getString(R.string.add_contact_ip_error_empty)
                    } else if (portNum == null || portNum < 1 || portNum > 65535) {
                        error = context.getString(R.string.add_contact_port_error_invalid)
                    } else {
                        onConnect(ipAddress.trim(), portNum)
                    }
                }
            ) {
                Text(stringResource(R.string.action_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ============================================================
// Hilfsfunktionen
// ============================================================

/**
 * Extrahiert die Peer-ID aus einem Crisix-QR-Code.
 * Format: "crisix://contact?key=<peerId>&name=<name>"
 */
private fun extractPeerIdFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("key")
    } catch (e: Exception) {
        null
    }
}

/**
 * Extrahiert den Namen aus einem Crisix-QR-Code.
 * Format: "crisix://contact?key=<peerId>&name=<name>"
 */
private fun extractNameFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("name")
    } catch (e: Exception) {
        null
    }
}

/**
 * Tritt einem geheimen Raum bei.
 * Der Raum-Name wird gehasht und als Topic in der DHT verwendet.
 */
private suspend fun joinSecretRoom(roomName: String, transportManager: TransportManager?) {
    // In einer vollständigen Implementierung:
    // 1. SHA-256-Hash des Raum-Namens berechnen
    // 2. DHT.announce(topic, eigenePeerId) aufrufen
    // 3. DHT.lookup(topic) für andere Peers im Raum
    // 4. Gefundene Peers als Kontakte speichern
    Log.i("AddContact", "Trete geheimem Raum bei: $roomName")
}

/**
 * Verbindet über eine Kurz-ID.
 * Sucht in der DHT nach Peers mit diesem Fingerprint.
 */
private suspend fun connectViaShortId(shortId: String, transportManager: TransportManager?) {
    // In einer vollständigen Implementierung:
    // 1. DHT.findPeersByFingerprint(shortId) aufrufen
    // 2. Gefundene Kandidaten dem Nutzer zur Auswahl anzeigen
    // 3. Ausgewählten Peer als Kontakt speichern
    Log.i("AddContact", "Suche nach Kurz-ID: $shortId")
}
