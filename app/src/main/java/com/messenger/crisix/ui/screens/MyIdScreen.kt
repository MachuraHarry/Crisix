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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
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
    displayName: String,
    onBackClick: () -> Unit,
    handshakeQrContent: String? = null,
    modifier: Modifier = Modifier
) {
    var peerId by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf(0) }
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handshakeQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrContent by remember { mutableStateOf("") }
    var localIp by remember { mutableStateOf<String?>(null) }
    var showHandshakeQr by remember { mutableStateOf(false) }

    // Peer-ID und Port abrufen
    LaunchedEffect(Unit) {
        peerId = Libp2pManager.localPeerId
        localPort = Libp2pManager.localPort

        if (peerId.isNotBlank()) {
            shortId = peerId.take(8)
        }

        localIp = getLocalIPv4Address()

        qrContent = buildString {
            append("crisix://contact?key=$peerId&name=$displayName")
            if (localIp != null) {
                append("&ip=$localIp")
            }
            if (localPort > 0) {
                append("&port=$localPort")
            }
        }
        qrCodeBitmap = generateQrCode(qrContent)

        if (handshakeQrContent != null) {
            handshakeQrBitmap = generateQrCode(handshakeQrContent)
        }
    }


    Scaffold(

        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.my_id_title),
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // === QR-Code (Contact oder E2EE Handshake) ===
            val activeBitmap = if (showHandshakeQr) handshakeQrBitmap else qrCodeBitmap
            if (activeBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = if (showHandshakeQr) "E2EE Handshake QR" else stringResource(R.string.my_id_qr_description),
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
                        text = stringResource(R.string.my_id_qr_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (showHandshakeQr) "E2EE Handshake — one-sided encrypted messaging" else stringResource(R.string.my_id_qr_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (handshakeQrBitmap != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showHandshakeQr = !showHandshakeQr }) {
                    Text(
                        text = if (showHandshakeQr) "Show Contact QR" else "Show E2EE Handshake QR",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // === 8-stellige Kurz-ID ===
            Text(
                text = stringResource(R.string.my_id_short_id_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = shortId.ifBlank { stringResource(R.string.my_id_loading) },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.my_id_short_id_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // === Vollständige Peer-ID ===
            Text(
                text = stringResource(R.string.my_id_peer_id_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = peerId.ifBlank { stringResource(R.string.my_id_loading) },
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
                text = stringResource(R.string.my_id_port_label, localPort),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // === Lokale IP ===
            Text(
                text = stringResource(R.string.my_id_local_ip_label, localIp ?: stringResource(R.string.my_id_ip_unknown)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // === QR-Code-Inhalt (Debug) ===
            Text(
                text = stringResource(R.string.my_id_qr_contains_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = qrContent.ifBlank { stringResource(R.string.my_id_loading) },
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
                text = stringResource(R.string.my_id_connect_instructions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            val hints = listOf(
                stringResource(R.string.my_id_hint_qr),
                stringResource(R.string.my_id_hint_secret_room),
                stringResource(R.string.my_id_hint_short_id),
                stringResource(R.string.my_id_hint_auto)
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
 *
 * ## Strategie
 * 1. Bevorzugt eine "echte" IP-Adresse (nicht 10.0.2.x, nicht Loopback)
 * 2. Fallback: Emulator-NAT-IP (10.0.2.x) – der Emulator hat zwar eine NAT-IP,
 *    aber diese ist für den QR-Code wichtig, damit andere Geräte im selben
 *    Netzwerk den Emulator finden können (über die Host-IP 10.0.2.2).
 *
 * @return Die beste verfügbare IPv4-Adresse, oder null
 */
private fun getLocalIPv4Address(): String? {
    var emulatorIp: String? = null
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
                        // Emulator-NAT-IP (10.0.2.x) merken, aber nicht sofort zurückgeben
                        if (host.startsWith("10.0.2.")) {
                            emulatorIp = host
                            continue
                        }
                        // Echte IP gefunden – sofort zurückgeben
                        return host
                    }
                }
            }
        }
        // Keine echte IP gefunden → Emulator-IP als Fallback
        emulatorIp
    } catch (e: Exception) {
        emulatorIp // Auch bei Exception: Emulator-IP als Fallback
    }
}
