package com.messenger.crisix.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.transport.ConnectionState
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.TransportType
import kotlinx.coroutines.launch

data class ChatPreview(
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val transportType: TransportType? = null
)

/**
 * Hilfsfunktion zur Bestimmung der Datumsgruppe.
 * Simuliert eine einfache Gruppierung basierend auf dem Timestamp-String.
 */
private fun getDateGroup(timestamp: String): String {
    return when {
        timestamp.contains(":") -> "Heute"
        timestamp == "Gestern" -> "Gestern"
        timestamp.startsWith("Diese Woche") -> "Diese Woche"
        else -> "Älter"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatPreview>,
    onChatClick: (String, String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddPeer: (String, String) -> Unit = { _, _ -> },
    localPeerId: String = "",
    localPort: Int = 0,
    onMyIdClick: () -> Unit = {},
    onAddContactClick: () -> Unit = {},
    onConnectionsClick: () -> Unit = {},
    onContactsClick: () -> Unit = {},
    connectionStatuses: Map<TransportType, ConnectionStatus> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    var peerIpAddress by remember { mutableStateOf("") }
    var peerName by remember { mutableStateOf("") }
    var addPeerError by remember { mutableStateOf<String?>(null) }
    var showPeerIdDialog by remember { mutableStateOf(false) }
    var peerIdInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Chats nach Suchbegriff filtern
    val filteredChats = remember(chats, searchQuery) {
        if (searchQuery.isBlank()) {
            chats
        } else {
            chats.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    // Chats nach Datumsgruppen sortieren
    val groupedChats = remember(filteredChats) {
        filteredChats.groupBy { getDateGroup(it.timestamp) }
    }

    // Reihenfolge der Datumsgruppen
    val groupOrder = listOf("Heute", "Gestern", "Diese Woche", "Älter")

    // Dialog zum Hinzufügen eines Peers per IP
    if (showAddPeerDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddPeerDialog = false
                peerIpAddress = ""
                peerName = ""
                addPeerError = null
            },
            title = {
                Text("Peer verbinden")
            },
            text = {
                Column {
                    // Eigene Peer-ID anzeigen (zum Teilen mit anderen)
                    if (localPeerId.isNotBlank()) {
                        Text(
                            text = "Deine Peer-ID:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = localPeerId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Port: $localPort",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (addPeerError != null) {
                        Text(
                            text = addPeerError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = "Gib die IP:Port des anderen Geräts ein:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerIpAddress,
                        onValueChange = { peerIpAddress = it },
                        label = { Text("IP-Adresse:Port") },
                        placeholder = { Text("z.B. 192.168.178.51:43155") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerName,
                        onValueChange = { peerName = it },
                        label = { Text("Name (optional)") },
                        placeholder = { Text("z.B. Pixel 9") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerIpAddress.isBlank()) {
                            addPeerError = "Bitte gib eine IP-Adresse ein"
                        } else {
                            addPeerError = null
                            onAddPeer(peerIpAddress.trim(), peerName.trim())
                            showAddPeerDialog = false
                            peerIpAddress = ""
                            peerName = ""
                        }
                    }
                ) {
                    Text("Verbinden")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddPeerDialog = false
                    peerIpAddress = ""
                    peerName = ""
                    addPeerError = null
                }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Gesamtstatus aus allen Transporten berechnen (vor Scaffold!)
    // ═══════════════════════════════════════════════════════════════
    // REGELN:
    // 🔴 ERROR (Rot)     → Mindestens ein Transport hat einen Fehler
    // 🟢 CONNECTED (Grün) → Mindestens ein Transport ist BEREIT (WLAN/Internet da)
    // 🟡 SEARCHING (Gelb) → Mindestens ein Transport startet noch
    // ⚪ UNAVAILABLE (Grau) → Kein Netzwerk (alle Transporte nicht verfügbar)
    val overallColor = remember(connectionStatuses) {
        if (connectionStatuses.isEmpty()) {
            Color(0xFF9E9E9E) // Grau wenn keine Status
        } else {
            val hasError = connectionStatuses.values.any { it.state == ConnectionState.ERROR }
            val hasConnected = connectionStatuses.values.any { it.state == ConnectionState.CONNECTED }
            val hasSearching = connectionStatuses.values.any { it.state == ConnectionState.SEARCHING }
            val allUnavailable = connectionStatuses.values.all { it.state == ConnectionState.UNAVAILABLE }

            when {
                hasError -> Color(0xFFF44336)          // 🔴 Fehler hat höchste Priorität
                hasConnected -> Color(0xFF4CAF50)      // 🟢 WLAN/Internet ist bereit
                hasSearching -> Color(0xFFFFC107)      // 🟡 Startet noch
                allUnavailable -> Color(0xFF9E9E9E)    // ⚪ Kein Netzwerk
                else -> Color(0xFF9E9E9E)              // ⚪ Standard
            }
        }
    }

    // Animierte Farbe für flüssige Übergänge
    val animatedColor by animateColorAsState(
        targetValue = overallColor,
        animationSpec = tween(durationMillis = 500),
        label = "statusColor"
    )

    // ═══════════════════════════════════════════════════════════════
    // Live-Status-Text unter dem Titel (wie WhatsApp "online")
    // Zeigt was gerade passiert – dynamisch und farbig
    // ═══════════════════════════════════════════════════════════════
    val statusText = remember(connectionStatuses) {
        if (connectionStatuses.isEmpty()) {
            "Starte..."
        } else {
            val connected = connectionStatuses.values.filter { it.state == ConnectionState.CONNECTED }
            val searching = connectionStatuses.values.filter { it.state == ConnectionState.SEARCHING }
            val errors = connectionStatuses.values.filter { it.state == ConnectionState.ERROR }
            val unavailable = connectionStatuses.values.filter { it.state == ConnectionState.UNAVAILABLE }

            when {
                // 🔴 Fehler
                errors.isNotEmpty() -> {
                    val errMsg = errors.firstNotNullOfOrNull { it.errorMessage }
                    if (errMsg != null) "⚠️ $errMsg" else "⚠️ Verbindungsfehler"
                }
                // 🟢 Verbunden – zeige Details
                connected.isNotEmpty() -> {
                    val parts = mutableListOf<String>()
                    connected.forEach { status ->
                        val name = when (status.transportType) {
                            TransportType.INTERNET -> "DHT"
                            TransportType.WIFI_DIRECT -> "WLAN"
                            TransportType.BLUETOOTH_MESH -> "BT"
                            else -> status.transportType.name.take(3)
                        }
                        // Peer-Anzahl (Crisix-Geräte)
                        if (status.peerCount > 0) {
                            parts.add("$name: ${status.peerCount}")
                        } else {
                            parts.add(name)
                        }
                        // Detail-Text (z.B. "8 DHT-Knoten" für BitTorrent-Knoten)
                        if (status.detailText.isNotBlank()) {
                            parts.add(status.detailText)
                        }
                    }
                    parts.joinToString(" · ")
                }
                // 🟡 Suche läuft
                searching.isNotEmpty() -> {
                    val names = searching.map {
                        when (it.transportType) {
                            TransportType.INTERNET -> "DHT"
                            TransportType.WIFI_DIRECT -> "WLAN"
                            TransportType.BLUETOOTH_MESH -> "BT"
                            else -> it.transportType.name.take(3)
                        }
                    }
                    "Suche ${names.joinToString(", ")}..."
                }
                // ⚪ Kein Netzwerk
                unavailable.size == connectionStatuses.size -> "Kein Netzwerk"
                // Mischzustand
                else -> {
                    val parts = mutableListOf<String>()
                    if (connected.isNotEmpty()) parts.add("${connected.size} verbunden")
                    if (searching.isNotEmpty()) parts.add("${searching.size} suche")
                    if (unavailable.isNotEmpty()) parts.add("${unavailable.size} offline")
                    parts.joinToString(" · ")
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearchActive) {
                // Suchleiste (WhatsApp-Stil)
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.search_close)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                // Normale TopBar
                TopAppBar(
                    title = {
                        Column {
                            // ═══════════════════════════════════════════════
                            // Zeile 1: "Crisix" mit farbigem Punkt DARUNTER
                            // ═══════════════════════════════════════════════
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.chat_list_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // ═══════════════════════════════════════════════
                            // Zeile 2: Farbiger Punkt + Live-Status-Text
                            // Zeigt was gerade passiert (wie Debug-Info)
                            // ═══════════════════════════════════════════════
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 🟢🟡⚪🔴 Farbiger Status-Punkt
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(animatedColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                // Live-Status-Text (dynamisch, farbig)
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = animatedColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        // Meine ID anzeigen (bleibt als eigener Button)
                        IconButton(onClick = onMyIdClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_person),
                                contentDescription = "Meine ID",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Such-Button
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = "Suchen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Bürger-Menü (Hamburger-Menü) für alle anderen Aktionen
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_menu),
                                    contentDescription = "Menü",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Neuer Kontakt") },
                                    onClick = {
                                        showMenu = false
                                        onAddContactClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_qr_code),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Kontakte") },
                                    onClick = {
                                        showMenu = false
                                        onContactsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_person),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Verbindungen") },
                                    onClick = {
                                        showMenu = false
                                        onConnectionsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_network),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Einstellungen") },
                                    onClick = {
                                        showMenu = false
                                        onSettingsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_settings),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        // ═══════════════════════════════════════════════════════════════
        // Dünne klickbare Status-Leiste unter der TopBar
        // Zeigt den Gesamtstatus als farbigen Balken an (3dp hoch)
        // ═══════════════════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clickable { onConnectionsClick() }
                .background(animatedColor)
        )
        if (filteredChats.isEmpty()) {
            // Leerer Zustand
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chat),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) stringResource(R.string.no_results) else stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) stringResource(R.string.no_results_subtitle) else stringResource(R.string.no_chats_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Chats nach Datumsgruppen anzeigen
                groupOrder.forEach { group ->
                    val chatsInGroup = groupedChats[group]
                    if (chatsInGroup != null) {
                        // Datumstrenner
                        item(key = "header_$group") {
                            DateGroupHeader(group = group)
                        }

                        // Chats in dieser Gruppe
                        items(
                            items = chatsInGroup,
                            key = { it.id }
                        ) { chat ->
                            ChatListItem(
                                chat = chat,
                                onClick = { onChatClick(chat.id, chat.name) }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateGroupHeader(group: String) {
    val groupLabel = when (group) {
        "Heute" -> stringResource(R.string.date_today)
        "Gestern" -> stringResource(R.string.date_yesterday)
        "Diese Woche" -> stringResource(R.string.date_this_week)
        else -> stringResource(R.string.date_older)
    }

    Text(
        text = groupLabel,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ChatListItem(
    chat: ChatPreview,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar mit Online-Indikator
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chat.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Kleiner Transport-Indikator unten rechts am Avatar
            if (chat.transportType != null) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_network),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text-Inhalt
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = chat.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.transportType != null) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_network),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Ungelesene Nachrichten-Badge
        if (chat.unreadCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chat.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
