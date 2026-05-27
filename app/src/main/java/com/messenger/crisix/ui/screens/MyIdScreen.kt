package com.messenger.crisix.ui.screens

import android.graphics.Bitmap
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.transport.internet.Libp2pManager
import java.net.Inet4Address
import java.net.NetworkInterface


/**
 * Bildschirm zur Anzeige der eigenen Crisix-ID.
 *
 * Zeigt:
 * - 8-stellige Kurz-ID (Fingerprint des Public Keys)
 * - Vollständige Peer-ID
 * - QR-Code zum Teilen mit anderen
 * - Port-Information
 * - QR-Code-Inhalt (Debug)
 * - DHT-Status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var peerId by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf(0) }
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrContent by remember { mutableStateOf("") }
    var localIp by remember { mutableStateOf<String?>(null) }

    // Peer-ID und Port abrufen
    LaunchedEffect(Unit) {
        peerId = Libp2pManager.localPeerId
        localPort = Libp2pManager.localPort

        // Kurz-ID (8-stelliger Fingerprint) generieren
        if (peerId.isNotBlank()) {
            shortId = peerId.take(8)
        }

        // Lokale IP-Adresse ermitteln
        localIp = getLocalIPv4Address()

        // QR-Code-Inhalt: "crisix://contact?key=<fingerprint>&name=<name>&ip=<ip>&port=<port>"
        //
        // ## Serverlose Vision (oberste Priorität)
        // Der QR-Code enthält den kryptografischen Fingerprint (Peer-ID) des Geräts.
        // Der Scanner kann diesen Fingerprint nutzen, um den Peer über die
        // globale Kademlia DHT zu finden – ohne zentrale Server!
        //
        // ## Fallback: IP-Adresse
        // Zusätzlich wird die lokale IP-Adresse eingebettet, damit der Scanner
        // den Peer auch direkt im lokalen Netzwerk finden kann (WifiTransport),
        // falls die DHT nicht verfügbar ist (z.B. kein Internet).
        //
        // ## Format
        // crisix://contact?key=<fingerprint>&name=Crisix-User&ip=<ip>&port=<port>
        qrContent = buildString {
            append("crisix://contact?key=$peerId&name=Crisix-User")
            if (localIp != null) {
                append("&ip=$localIp")
            }
            if (localPort > 0) {
                append("&port=$localPort")
            }
        }
        qrCodeBitmap = generateQrCode(qrContent)
    }


    Scaffold(

        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Meine ID",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // === QR-Code ===
            if (qrCodeBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = qrCodeBitmap!!.asImageBitmap(),
                        contentDescription = "QR-Code der Crisix-ID",
                        modifier = Modifier.size(196.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Lade QR-Code...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Scanne diesen Code mit einem anderen Crisix-Gerät",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // === 8-stellige Kurz-ID ===
            Text(
                text = "Deine Kurz-ID",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = shortId.ifBlank { "Lade..." },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Zum Teilen mit anderen (manuelle Eingabe)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // === Vollständige Peer-ID ===
            Text(
                text = "Peer-ID (vollständig)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = peerId.ifBlank { "Lade..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === Port ===
            Text(
                text = "Port: $localPort",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // === Lokale IP ===
            Text(
                text = "Lokale IP: ${localIp ?: "Nicht ermittelbar"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === QR-Code-Inhalt (Debug) ===
            Text(
                text = "QR-Code enthält:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = qrContent.ifBlank { "Lade..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // === Hinweise ===
            Text(
                text = "So verbindest du dich mit anderen:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            val hints = listOf(
                "📷  QR-Code scannen – Primärer Weg, sicher und einfach",
                "🏠  Geheimer Raum – Persönlich vereinbarten Namen eingeben",
                "🔤  Kurz-ID eingeben – Fallback, wenn QR-Code nicht möglich",
                "🌐  mDNS/BLE – Automatisch im selben WLAN oder in der Nähe"
            )
            hints.forEach { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Generiert einen echten QR-Code als Bitmap mit ZXing.
 * ZXing ist bereits als Dependency eingebunden (com.google.zxing:core).
 */
private fun generateQrCode(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val size = 512
        val margin = 20 // Weißer Rand um den QR-Code

        // BitMatrix vom ZXing Writer generieren lassen
        val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)

        // BitMatrix in Bitmap rendern
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        android.util.Log.e("MyIdScreen", "QR-Code-Generierung fehlgeschlagen", e)
        null
    }
}

/**
 * Ermittelt die lokale IPv4-Adresse des Geräts.
 * Überspringt Loopback- und Emulator-Interfaces (10.0.2.x).
 */
private fun getLocalIPv4Address(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // Emulator-Netzwerk (10.0.2.x) überspringen
                        if (host.startsWith("10.0.2.")) continue
                        return host
                    }
                }
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}
