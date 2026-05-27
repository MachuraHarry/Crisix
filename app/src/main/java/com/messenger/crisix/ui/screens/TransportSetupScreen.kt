package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.transport.TransportType

@Composable
fun TransportSetupScreen(
    transportSettings: Map<TransportType, Boolean>,
    onTransportToggle: (TransportType, Boolean) -> Unit,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Transportwege",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Wähle, welche Wege Crisix zum Senden und Empfangen von Nachrichten nutzen darf.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            transportSettings.forEach { (type, enabled) ->
                TransportSetupItem(
                    transportType = type,
                    enabled = enabled,
                    onToggle = { onTransportToggle(type, it) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "Fertig",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TransportSetupItem(
    transportType: TransportType,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val (label, description, color) = transportInfo(transportType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.3f)
            )
        )
    }
}

private fun transportInfo(type: TransportType): Triple<String, String, Color> {
    return when (type) {
        TransportType.RELAY -> Triple(
            "Relay",
            "Weiterleitung über einen zentralen Server",
            Color(0xFF9C27B0)
        )
        TransportType.INTERNET -> Triple(
            "Internet (DHT)",
            "P2P-Verbindung über das Internet via DHT",
            Color(0xFF1976D2)
        )
        TransportType.WIFI_DIRECT -> Triple(
            "WLAN-Direkt",
            "Lokale P2P-Verbindung im selben Netzwerk",
            Color(0xFF388E3C)
        )
        TransportType.BLUETOOTH_MESH -> Triple(
            "Bluetooth-Mesh",
            "Nahbereichs-Kommunikation via Bluetooth",
            Color(0xFF00838F)
        )
        TransportType.SMS -> Triple(
            "SMS",
            "Nachrichten via SMS (kostenpflichtig)",
            Color(0xFFE65100)
        )
        TransportType.DNS_TUNNEL -> Triple(
            "DNS-Tunnel",
            "Fallback via DNS-Anfragen (langsam)",
            Color(0xFF5D4037)
        )
        TransportType.LORA -> Triple(
            "LoRa",
            "Langstrecken-Funk via LoRaWAN",
            Color(0xFF37474F)
        )
    }
}
