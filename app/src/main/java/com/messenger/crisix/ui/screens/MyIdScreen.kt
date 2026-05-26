package com.messenger.crisix.ui.screens

import android.graphics.Bitmap
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
import com.messenger.crisix.transport.internet.CryptoHelper
import com.messenger.crisix.transport.internet.Libp2pManager

/**
 * Bildschirm zur Anzeige der eigenen Crisix-ID.
 *
 * Zeigt:
 * - 8-stellige Kurz-ID (Fingerprint des Public Keys)
 * - Vollständige Peer-ID
 * - QR-Code zum Teilen mit anderen
 * - Port-Information
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

    // Peer-ID und Port abrufen
    LaunchedEffect(Unit) {
        peerId = Libp2pManager.localPeerId
        localPort = Libp2pManager.localPort

        // Kurz-ID (8-stelliger Fingerprint) generieren
        if (peerId.isNotBlank()) {
            shortId = peerId.take(8)
        }

        // QR-Code-Inhalt: "crisix://contact?key=<publicKey>&name=<name>"
        val qrContent = "crisix://contact?key=$peerId&name=Crisix-User"
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
 * Generiert einen einfachen QR-Code als Bitmap.
 * Verwendet einen vereinfachten Algorithmus – in der Produktion
 * sollte eine Bibliothek wie ZXing verwendet werden.
 */
private fun generateQrCode(content: String): Bitmap? {
    return try {
        // Vereinfachte QR-Code-Generierung
        // In der Produktion: ZXing "com.google.zxing:core" verwenden
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        // Einfaches Muster als Platzhalter
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.FILL
        }

        // 4 Eckmarkierungen (wie QR-Code)
        val markerSize = size / 5
        val positions = listOf(
            0 to 0,
            size - markerSize to 0,
            0 to size - markerSize
        )
        for ((x, y) in positions) {
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + markerSize).toFloat(), (y + markerSize).toFloat(), paint)
            paint.color = android.graphics.Color.WHITE
            canvas.drawRect(
                (x + markerSize * 0.2f), (y + markerSize * 0.2f),
                (x + markerSize * 0.8f), (y + markerSize * 0.8f),
                paint
            )
            paint.color = android.graphics.Color.BLACK
            canvas.drawRect(
                (x + markerSize * 0.35f), (y + markerSize * 0.35f),
                (x + markerSize * 0.65f), (y + markerSize * 0.65f),
                paint
            )
        }

        // Datenbits aus dem Content-Hash
        paint.color = android.graphics.Color.BLACK
        val hash = content.hashCode()
        val rng = java.util.Random(hash.toLong())
        val cellSize = size / 25
        for (row in 0 until 25) {
            for (col in 0 until 25) {
                // Überspringe Bereiche der Marker
                val inTopLeft = col < 7 && row < 7
                val inTopRight = col > 17 && row < 7
                val inBottomLeft = col < 7 && row > 17
                if (inTopLeft || inTopRight || inBottomLeft) continue

                if (rng.nextBoolean()) {
                    canvas.drawRect(
                        (col * cellSize).toFloat(),
                        (row * cellSize).toFloat(),
                        ((col + 1) * cellSize).toFloat(),
                        ((row + 1) * cellSize).toFloat(),
                        paint
                    )
                }
            }
        }

        bitmap
    } catch (e: Exception) {
        null
    }
}
