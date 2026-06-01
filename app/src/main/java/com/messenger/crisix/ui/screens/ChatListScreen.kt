package com.messenger.crisix.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.transport.ConnectionState
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.TransportType
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

@Stable
data class ChatPreview(
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String,
    val timestampMillis: Long = 0L,
    val unreadCount: Int = 0,
    val transportType: TransportType? = null,
    val pinned: Boolean = false
)

private enum class DateGroup { TODAY, YESTERDAY, THIS_WEEK, OLDER }

private fun getDateGroup(timestampMillis: Long): DateGroup {
    if (timestampMillis == 0L) return DateGroup.OLDER
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestampMillis }

    if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)) {
        return DateGroup.TODAY
    }

    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (yesterday.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && yesterday.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)) {
        return DateGroup.YESTERDAY
    }

    if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && now.get(Calendar.WEEK_OF_YEAR) == msgTime.get(Calendar.WEEK_OF_YEAR)) {
        return DateGroup.THIS_WEEK
    }

    return DateGroup.OLDER
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
    onDeleteChat: (String) -> Unit = {},
    onPinChat: (String) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
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
    var chatIdForMenu by remember { mutableStateOf<String?>(null) }
    var pendingDeleteChat by remember { mutableStateOf<ChatPreview?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Chats nach Suchbegriff filtern
    val filteredChats = remember(chats, searchQuery, pendingDeleteChat) {
        var result = if (searchQuery.isBlank()) {
            chats
        } else {
            chats.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        pendingDeleteChat?.let { pending ->
            result = result.filter { it.id != pending.id }
        }
        result
    }

    // Chats nach Datumsgruppen sortieren
    val groupedChats = remember(filteredChats) {
        filteredChats.groupBy { getDateGroup(it.timestampMillis) }
    }

    // Reihenfolge der Datumsgruppen
    val groupOrder = listOf(DateGroup.TODAY, DateGroup.YESTERDAY, DateGroup.THIS_WEEK, DateGroup.OLDER)

    val ipAddressError = stringResource(R.string.chat_list_ip_error)

    // Undo-Delete via Snackbar: when pendingDeleteChat is set, show Snackbar with 5s timeout
    val undoLabel = stringResource(R.string.action_undo)
    val ctx = LocalContext.current
    LaunchedEffect(pendingDeleteChat) {
        val chat = pendingDeleteChat ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = ctx.getString(R.string.chat_list_delete_body, chat.name),
            actionLabel = undoLabel,
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> {
                pendingDeleteChat = null
            }
            SnackbarResult.Dismissed -> {
                val stillPending = pendingDeleteChat
                if (stillPending != null) {
                    onDeleteChat(stillPending.id)
                    pendingDeleteChat = null
                }
            }
        }
    }

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
                Text(stringResource(R.string.chat_list_connect_peer))
            },
            text = {
                Column {
                    // Eigene Peer-ID anzeigen (zum Teilen mit anderen)
                    if (localPeerId.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.chat_list_your_peer_id),
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
                            text = stringResource(R.string.chat_list_port_label, localPort),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    addPeerError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = stringResource(R.string.chat_list_enter_ip_port),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerIpAddress,
                        onValueChange = { peerIpAddress = it },
                        label = { Text(stringResource(R.string.chat_list_ip_port_label)) },
                        placeholder = { Text(stringResource(R.string.chat_list_ip_port_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerName,
                        onValueChange = { peerName = it },
                        label = { Text(stringResource(R.string.chat_list_name_optional_label)) },
                        placeholder = { Text(stringResource(R.string.chat_list_name_optional_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerIpAddress.isBlank()) {
                            addPeerError = ipAddressError
                        } else {
                            addPeerError = null
                            onAddPeer(peerIpAddress.trim(), peerName.trim())
                            showAddPeerDialog = false
                            peerIpAddress = ""
                            peerName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddPeerDialog = false
                    peerIpAddress = ""
                    peerName = ""
                    addPeerError = null
                }) {
                    Text(stringResource(R.string.action_cancel))
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
    val context = LocalContext.current
    val statusText = remember(connectionStatuses) {
        if (connectionStatuses.isEmpty()) {
            context.getString(R.string.chat_list_status_starting)
        } else {
            val connected = connectionStatuses.values.filter { it.state == ConnectionState.CONNECTED }
            val searching = connectionStatuses.values.filter { it.state == ConnectionState.SEARCHING }
            val errors = connectionStatuses.values.filter { it.state == ConnectionState.ERROR }
            val unavailable = connectionStatuses.values.filter { it.state == ConnectionState.UNAVAILABLE }

            when {
                errors.isNotEmpty() -> {
                    val errMsg = errors.mapNotNull { it.errorMessage }.firstOrNull()
                    if (errMsg != null) "⚠️ $errMsg" else context.getString(R.string.chat_list_status_connection_error)
                }
                connected.isNotEmpty() -> {
                    val parts = mutableListOf<String>()
                    connected.forEach { status ->
                        val name = when (status.transportType) {
                            TransportType.INTERNET -> "DHT"
                            TransportType.WIFI_DIRECT -> context.getString(R.string.chat_list_status_wifi)
                            TransportType.BLUETOOTH_MESH -> "BT"
                            else -> status.transportType.name.take(3)
                        }
                        if (status.peerCount > 0) {
                            parts.add("$name: ${status.peerCount}")
                        } else {
                            parts.add(name)
                        }
                        if (status.detailText.isNotBlank()) {
                            parts.add(status.detailText)
                        }
                    }
                    parts.joinToString(" · ")
                }
                searching.isNotEmpty() -> {
                    val names = searching.map {
                        when (it.transportType) {
                            TransportType.INTERNET -> "DHT"
                            TransportType.WIFI_DIRECT -> context.getString(R.string.chat_list_status_wifi)
                            TransportType.BLUETOOTH_MESH -> "BT"
                            else -> it.transportType.name.take(3)
                        }
                    }
                    context.getString(R.string.chat_list_status_searching, names.joinToString(", "))
                }
                unavailable.size == connectionStatuses.size -> context.getString(R.string.chat_list_status_no_network)
                else -> {
                    val parts = mutableListOf<String>()
                    if (connected.isNotEmpty()) parts.add(context.getString(R.string.chat_list_status_connected_count, connected.size))
                    if (searching.isNotEmpty()) parts.add(context.getString(R.string.chat_list_status_searching_count, searching.size))
                    if (unavailable.isNotEmpty()) parts.add(context.getString(R.string.chat_list_status_offline_count, unavailable.size))
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
                                contentDescription = stringResource(R.string.my_id_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Such-Button
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = stringResource(R.string.search_icon),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Bürger-Menü (Hamburger-Menü) für alle anderen Aktionen
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_menu),
                                    contentDescription = stringResource(R.string.chat_list_menu),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_list_new_contact)) },
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
                                    text = { Text(stringResource(R.string.chat_list_contacts)) },
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
                                    text = { Text(stringResource(R.string.chat_list_connections)) },
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
                                    text = { Text(stringResource(R.string.chat_list_settings)) },
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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddContactClick() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = stringResource(R.string.chat_list_new_contact),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                onRefresh?.invoke()
                scope.launch {
                    delay(800)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
        if (filteredChats.isEmpty()) {
            // Leerer Zustand
            val isSearchEmpty = searchQuery.isNotBlank()
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chat),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isSearchEmpty) stringResource(R.string.no_results) else stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isSearchEmpty) stringResource(R.string.no_results_subtitle) else stringResource(R.string.no_chats_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (!isSearchEmpty) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { onAddContactClick() },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_list_new_contact))
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
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
                }

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
                            val dismissState = rememberSwipeToDismissBoxState()
                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    pendingDeleteChat = chat
                                    dismissState.reset()
                                }
                            }
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Color(0xFFE53935),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .padding(end = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_delete),
                                            contentDescription = stringResource(R.string.action_delete),
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = true
                            ) {
                                ChatListItem(
                                    chat = chat,
                                    onClick = { onChatClick(chat.id, chat.name) },
                                    onDeleteClick = { pendingDeleteChat = chat },
                                    onPinClick = { onPinChat(chat.id) }
                                )
                            }
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
}

private fun transportIcon(type: TransportType): Int = when (type) {
    TransportType.WIFI_DIRECT -> R.drawable.ic_wifi
    TransportType.BLUETOOTH_MESH -> R.drawable.ic_bluetooth
    TransportType.INTERNET -> R.drawable.ic_globe
    TransportType.RELAY -> R.drawable.ic_cloud
    TransportType.DNS_TUNNEL -> R.drawable.ic_dns
    TransportType.SMS -> R.drawable.ic_sms
    TransportType.LORA -> R.drawable.ic_lora
}

@Composable
private fun DateGroupHeader(group: DateGroup) {
    val label = when (group) {
        DateGroup.TODAY -> stringResource(R.string.date_today)
        DateGroup.YESTERDAY -> stringResource(R.string.date_yesterday)
        DateGroup.THIS_WEEK -> stringResource(R.string.date_this_week)
        DateGroup.OLDER -> stringResource(R.string.date_older)
    }

    Text(
        text = label,
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
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    onPinClick: (() -> Unit)? = null
) {
    var showItemMenu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (onDeleteClick != null) {{ showItemMenu = true }} else null,
                onLongClickLabel = stringResource(R.string.chat_list_delete_chat)
            )
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
                        painter = painterResource(id = transportIcon(chat.transportType)),
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
                if (chat.pinned) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_push_pin),
                        contentDescription = stringResource(R.string.chat_list_pinned),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
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
                        painter = painterResource(id = transportIcon(chat.transportType)),
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
    DropdownMenu(
        expanded = showItemMenu,
        onDismissRequest = { showItemMenu = false },
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (onPinClick != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (chat.pinned) stringResource(R.string.chat_list_unpin)
                        else stringResource(R.string.chat_list_pin)
                    )
                },
                onClick = {
                    showItemMenu = false
                    onPinClick()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_push_pin),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
                showItemMenu = false
                onDeleteClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
    }
}
