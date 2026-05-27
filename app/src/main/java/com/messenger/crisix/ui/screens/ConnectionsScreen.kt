package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.transport.ConnectionState
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.DnsTunnelTransport
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import kotlinx.coroutines.launch

/**
 * Bildschirm zur Anzeige aller Verbindungsstatus und entdeckten Peers.
 *
 * Zeigt:
 * - Detaillierte Status-Karten für jeden Transportweg (DHT, WLAN, Bluetooth, etc.)
 * - Entdeckte Peers mit Verbindungsinfo
 * - Fehlermeldungen bei Problemen
 * - DNS-Tunnel-Test-Button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    transportManager: TransportManager?,
    onBackClick: () -> Unit,
    onPeerClick: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val discoveredPeers by (transportManager?.discoveredPeers ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<Peer>())).collectAsState()
    val connectionStatuses by (transportManager?.connectionStatuses ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<TransportType, ConnectionStatus>())).collectAsState()

    // State für DNS-Tunnel-Test
    var dnsTestResult by remember { mutableStateOf<String?>(null) }
    var isDnsTestRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Verbindungen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Zurück"
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // === Überschrift: Verbindungsstatus ===
            item {
                Text(
                    text = "Verbindungsstatus",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // === Status-Karten für jeden Transport ===
            val transportOrder = listOf(
                TransportType.INTERNET,
                TransportType.WIFI_DIRECT,
                TransportType.BLUETOOTH_MESH,
                TransportType.SMS,
                TransportType.DNS_TUNNEL,
                TransportType.LORA
            )

            transportOrder.forEach { type ->
                val status = connectionStatuses[type]
                item(key = "status_$type") {
                    TransportStatusCard(
                        type = type,
                        status = status
                    )
                }
            }

            // === DNS-Tunnel-Test-Button ===
            item {
                Spacer(modifier = Modifier.height(8.dp))
                DnsTunnelTestButton(
                    transportManager = transportManager,
                    dnsTestResult = dnsTestResult,
                    isDnsTestRunning = isDnsTestRunning,
                    onStartTest = {
                        dnsTestResult = null
                        isDnsTestRunning = true
                        scope.launch {
                            try {
                                val dnsTransport = transportManager?.getTransportByType(
                                    TransportType.DNS_TUNNEL
                                ) as? DnsTunnelTransport
                                if (dnsTransport != null) {
                                    dnsTestResult = dnsTransport.testConnection()
                                } else {
                                    dnsTestResult = "❌ DNS-Tunnel-Transport nicht gefunden.\n" +
                                            "Stelle sicher, dass DNS-Tunnel in den Einstellungen aktiviert ist."
                                }
                            } catch (e: Exception) {
                                dnsTestResult = "❌ Test fehlgeschlagen: ${e.message}"
                            } finally {
                                isDnsTestRunning = false
                            }
                        }
                    }
                )
            }

            // === Trennlinie ===
            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // === Überschrift: Entdeckte Geräte ===
            item {
                Text(
                    text = "Entdeckte Geräte (${discoveredPeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (discoveredPeers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_network),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Keine Geräte gefunden",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Scanne einen QR-Code oder warte auf automatische Erkennung",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                items(
                    items = discoveredPeers,
                    key = { it.id }
                ) { peer ->
                    PeerListItem(
                        peer = peer,
                        connectionStatuses = connectionStatuses,
                        onClick = { onPeerClick(peer.id, peer.name) }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 72.dp)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * DNS-Tunnel-Test-Button mit Ergebnis-Anzeige.
 * Führt einen vollständigen Test des DNS-Tunnels durch:
 * Ping → Senden → Polling → Health-Check
 */
@Composable
private fun DnsTunnelTestButton(
    transportManager: TransportManager?,
    dnsTestResult: String?,
    isDnsTestRunning: Boolean,
    onStartTest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Überschrift
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_network),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DNS-Tunnel Test",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Button
            Button(
                onClick = onStartTest,
                enabled = !isDnsTestRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDnsTestRunning)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isDnsTestRunning) "🔍 Test läuft..." else "🧪 Test starten",
                    fontWeight = FontWeight.Bold
                )
            }

            // Ergebnis anzeigen
            if (dnsTestResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = dnsTestResult,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Status-Karte für einen einzelnen Transportweg.
 * Zeigt Icon, Namen, Status, Detailtext und ggf. Fehler.
 */
@Composable
private fun TransportStatusCard(
    type: TransportType,
    status: ConnectionStatus?
) {
    val (iconRes, label) = when (type) {
        TransportType.INTERNET -> R.drawable.ic_network to "Internet (DHT)"
        TransportType.WIFI_DIRECT -> R.drawable.ic_wifi to "WLAN (mDNS)"
        TransportType.BLUETOOTH_MESH -> R.drawable.ic_bluetooth to "Bluetooth Mesh"
        TransportType.SMS -> R.drawable.ic_sms to "SMS"
        TransportType.DNS_TUNNEL -> R.drawable.ic_network to "DNS-Tunnel"
        TransportType.LORA -> R.drawable.ic_network to "LoRa"
        TransportType.RELAY -> R.drawable.ic_network to "Relay"
    }

    val state = status?.state ?: ConnectionState.DISABLED
    val (stateColor, stateText) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Verbunden"
        ConnectionState.SEARCHING -> Color(0xFFFFC107) to "Suche..."
        ConnectionState.UNAVAILABLE -> Color(0xFF9E9E9E) to "Nicht verfügbar"
        ConnectionState.DISABLED -> Color(0xFF9E9E9E) to "Deaktiviert"
        ConnectionState.ERROR -> Color(0xFFF44336) to "Fehler"
    }

    val detailText = status?.detailText ?: when (state) {
        ConnectionState.DISABLED -> "In den Einstellungen deaktiviert"
        ConnectionState.UNAVAILABLE -> "Hardware oder Netzwerk nicht verfügbar"
        ConnectionState.SEARCHING -> "Wird gestartet..."
        ConnectionState.CONNECTED -> "Bereit"
        ConnectionState.ERROR -> "Fehler beim Start"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = stateColor
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Status-Badge
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Detail-Text
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Peer-Anzahl (falls > 0)
                if (status != null && status.peerCount > 0) {
                    Text(
                        text = "${status.peerCount} Peer(s) gefunden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Fehlermeldung (falls vorhanden)
                if (status?.errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠ ${status.errorMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

/**
 * Einzelner Peer-Eintrag in der Verbindungsliste.
 * Zeigt an, über welchen Transport der Peer verbunden ist.
 */
@Composable
private fun PeerListItem(
    peer: Peer,
    connectionStatuses: Map<TransportType, ConnectionStatus>,
    onClick: () -> Unit
) {
    // Ermittle den Transport-Typ aus der Peer-ID
    // WifiTransport-Peers haben Format "UUID@IP"
    // InternetTransport-Peers haben Format "12D3KooW..."
    val transportType = when {
        peer.id.contains("@") -> TransportType.WIFI_DIRECT
        peer.id.startsWith("12D3") || peer.id.startsWith("16Uiu2") -> TransportType.INTERNET
        else -> null
    }

    val (iconRes, transportLabel) = when (transportType) {
        TransportType.WIFI_DIRECT -> R.drawable.ic_wifi to "WLAN"
        TransportType.INTERNET -> R.drawable.ic_network to "Internet (DHT)"
        else -> R.drawable.ic_network to "Unbekannt"
    }

    val transportStatus = if (transportType != null) connectionStatuses[transportType] else null
    val isConnected = transportStatus?.state == ConnectionState.CONNECTED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peer.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$transportLabel • ID: ${peer.id.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Status-Indikator
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
        )
    }
}
