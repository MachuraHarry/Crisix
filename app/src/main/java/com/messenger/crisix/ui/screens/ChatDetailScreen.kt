package com.messenger.crisix.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.messenger.crisix.R
import com.messenger.crisix.data.MessageEntity
import com.messenger.crisix.data.toMessage
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportCapabilities
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.AdaptiveInputBar
import com.messenger.crisix.ui.components.CapabilityBadge
import com.messenger.crisix.ui.components.ChatSearchBar
import com.messenger.crisix.ui.components.ImagePreviewDialog
import com.messenger.crisix.ui.components.MediaGalleryDialog
import com.messenger.crisix.ui.components.Message
import com.messenger.crisix.ui.components.MessageBubble
import com.messenger.crisix.util.getDateGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    chatName: String,
    transportType: TransportType?,
    capabilities: TransportCapabilities,
    messagesFlow: Flow<PagingData<MessageEntity>>,
    incomingTransports: Map<String, TransportType> = emptyMap(),
    onBackClick: () -> Unit,
    onSendMessage: (String, String?, String?, String?) -> Unit,
    onSendImage: ((Uri) -> Unit)? = null,
    onSendVoice: ((ByteArray, Long) -> Unit)? = null,
    isE2eeEnabled: Boolean = false,
    onMarkChatAsRead: (() -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    disappearingTimerMs: Long = 0L,
    onSetDisappearingTimer: ((Long) -> Unit)? = null,
    onCleanExpiredMessages: (suspend () -> Int)? = null,
    modifier: Modifier = Modifier
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var pendingVoiceStart by remember { mutableStateOf(false) }
    var e2eeStatusMessage by remember { mutableStateOf<String?>(null) }
    var messageToDelete by remember { mutableStateOf<String?>(null) }
    var replyTarget by remember { mutableStateOf<Message?>(null) }
    val meLabel = stringResource(R.string.chat_detail_reply_me)
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatchIndex by remember { mutableStateOf(0) }
    var showMediaGallery by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var disappearingTimerMs by remember { mutableStateOf(disappearingTimerMs) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val lazyEntities = messagesFlow.collectAsLazyPagingItems()

    LaunchedEffect(disappearingTimerMs) {
        onSetDisappearingTimer?.invoke(disappearingTimerMs)
    }

    LaunchedEffect(chatId) {
        while (true) {
            delay(15_000L)
            val deleted = onCleanExpiredMessages?.invoke() ?: 0
            if (deleted > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.timer_messages_deleted, deleted)
                )
            }
        }
    }

    // E2EE-Status-Snackbar anzeigen, wenn sich der Status ändert
    LaunchedEffect(isE2eeEnabled) {
        if (isE2eeEnabled) {
            e2eeStatusMessage = null
        }
    }

    // Markiere Chat als gelesen, wenn dieser Bildschirm angezeigt wird
    LaunchedEffect(chatId) {
        onMarkChatAsRead?.invoke()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendImage?.invoke(uri)
        }
    }

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { onSendImage?.invoke(it) }
        }
    }

    var showMediaPicker by remember { mutableStateOf(false) }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                lastVisible != null && lastVisible.index >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(lazyEntities.itemCount) {
        if (lazyEntities.itemCount > 0 && isAtBottom) {
            listState.animateScrollToItem(lazyEntities.itemCount - 1)
        }
    }

    val transportLabel = when (transportType) {
        TransportType.RELAY -> stringResource(R.string.transport_relay_label)
        TransportType.INTERNET -> stringResource(R.string.transport_internet)
        TransportType.WIFI_DIRECT -> stringResource(R.string.transport_wifi_direct)
        TransportType.BLUETOOTH_MESH -> stringResource(R.string.transport_bluetooth)
        TransportType.SMS -> stringResource(R.string.transport_sms)
        TransportType.DNS_TUNNEL -> stringResource(R.string.transport_dns_tunnel)
        TransportType.LORA -> stringResource(R.string.transport_lora)
        null -> stringResource(R.string.transport_offline)
    }

    val uri = previewImageUri
    if (uri != null) {
        ImagePreviewDialog(
            imageUri = uri,
            onDismiss = { previewImageUri = null },
        )
    }

    if (messageToDelete != null) {
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text(stringResource(R.string.chat_detail_delete_title)) },
            text = { Text(stringResource(R.string.chat_detail_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val msgId = messageToDelete
                    if (msgId != null) {
                        onDeleteMessage?.invoke(msgId)
                    }
                    messageToDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showMediaPicker) {
        ModalBottomSheet(
            onDismissRequest = { showMediaPicker = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.attach_choose),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMediaPicker = false
                            val imagesDir = java.io.File(context.filesDir, "images")
                            imagesDir.mkdirs()
                            val file = java.io.File(imagesDir, "camera_${System.currentTimeMillis()}.jpg")
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            cameraImageUri = uri
                            cameraLauncher.launch(uri)
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_attach),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.attach_camera),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMediaPicker = false
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chat),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.attach_gallery),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearchActive) {
                ChatSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it; searchMatchIndex = 0 },
                    matchIndex = searchMatchIndex,
                    onPrevious = { searchMatchIndex-- },
                    onNext = { searchMatchIndex++ },
                    entities = lazyEntities,
                    listState = listState,
                    scope = scope,
                    onClose = { isSearchActive = false; searchQuery = ""; searchMatchIndex = 0 }
                )
            } else {
                TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chatName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = chatName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (transportType != null) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_network),
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = transportLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.transport_offline),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                // E2EE-Schloss-Icon
                                if (isE2eeEnabled) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "\uD83D\uDD12",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_menu),
                                contentDescription = stringResource(R.string.action_more)
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_search)) },
                                onClick = {
                                    showOverflowMenu = false
                                    isSearchActive = true
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(id = R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.media_gallery_title, "")) },
                                onClick = {
                                    showOverflowMenu = false
                                    showMediaGallery = true
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(id = R.drawable.ic_attach), contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timer_title)) },
                                onClick = {
                                    showOverflowMenu = false
                                    showTimerDialog = true
                                },
                                leadingIcon = {
                                    Text(if (disappearingTimerMs > 0L) formatTimerShort(disappearingTimerMs) else "\u23F0", modifier = Modifier.size(20.dp))
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
        bottomBar = {
            Column {
                CapabilityBadge(
                    transportType = transportType,
                    capabilities = capabilities
                )
                AdaptiveInputBar(
                    messageText = messageText,
                    onMessageChange = { messageText = it },
                    onSend = {
                        if (messageText.isNotBlank()) {
                            val target = replyTarget
                            onSendMessage(
                                messageText,
                                target?.id,
                                target?.text,
                                target?.replyToSender ?: target?.let { if (it.isFromMe) meLabel else chatName }
                            )
                            messageText = ""
                            replyTarget = null
                        }
                    },
                    onAttachClick = {
                        showMediaPicker = true
                    },
                    isRecording = isRecording,
                    onVoiceStart = {
                        // UI sofort in Recording-Modus schalten
                        isRecording = true
                        // Permission wurde bereits auf der PermissionSetupScreen angefragt
                        startRecording(context, scope, snackbarHostState)
                    },
                    onVoiceEnd = {
                        scope.launch {
                            try {
                                val (audioBytes, durationMs) = com.messenger.crisix.util.AudioRecorder.stopRecording()
                                if (audioBytes.isNotEmpty()) {
                                    onSendVoice?.invoke(audioBytes, durationMs)
                                }
                            } finally {
                                isRecording = false
                            }
                        }
                    },
                    onVoiceCancel = {
                        com.messenger.crisix.util.AudioRecorder.cancelRecording()
                        isRecording = false
                    },
                    capabilities = capabilities,
                    isE2eeEnabled = isE2eeEnabled,
                    replyTarget = replyTarget,
                    onClearReply = { replyTarget = null }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!isAtBottom && lazyEntities.itemCount > 0) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(lazyEntities.itemCount - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Text("↓", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { innerPadding ->
        if (lazyEntities.itemCount == 0 && lazyEntities.loadState.refresh !is androidx.paging.LoadState.Loading) {
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_messages),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.no_messages_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                    val loadState = lazyEntities.loadState
                    if (loadState.refresh is androidx.paging.LoadState.Loading) {
                        item(key = "loading_top") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(16.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                    items(
                        count = lazyEntities.itemCount,
                        key = { index -> lazyEntities[index]?.id ?: index }
                    ) { index ->
                        lazyEntities[index]?.toMessage()?.let { message ->
                            val showDateSeparator = if (message.isSystemMessage) false
                            else if (index == 0) true
                            else {
                                val prev = lazyEntities[index - 1]?.timestampMillis
                                prev != null && getDateGroup(message.timestampMillis) != getDateGroup(prev)
                            }
                            val isGrouped = !message.isSystemMessage && index > 0 &&
                                lazyEntities[index - 1]?.toMessage()?.let { prev ->
                                    prev.isFromMe == message.isFromMe &&
                                    kotlin.math.abs(message.timestampMillis - prev.timestampMillis) < 60_000L
                                } == true

                            if (showDateSeparator) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dateLabel(message.timestampMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            MessageBubble(
                                message = message,
                                context = context,
                                chatId = chatId,
                                incomingTransport = incomingTransports[chatId],
                                showMetadata = !isGrouped,
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_detail_clipboard_label), message.text))
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.message_copied)
                                        )
                                    }
                                },
                                onDelete = { messageToDelete = message.id },
                                onReply = if (!message.isSystemMessage) {
                                    { replyTarget = message }
                                } else null,
                                onImageClick = { uri ->
                                    previewImageUri = uri
                                },
                            )
                }
            }
        }
            }
            if (showMediaGallery) {
                val mediaMsgs = remember(lazyEntities.itemSnapshotList) {
                    lazyEntities.itemSnapshotList
                        .filterNotNull()
                        .filter { it.imageUri != null || it.audioUri != null }
                        .map { it.toMessage() }
                }
                MediaGalleryDialog(
                    chatName = chatName,
                    mediaItems = mediaMsgs,
                    onDismiss = { showMediaGallery = false },
                    onImageClick = { uri -> previewImageUri = uri; showMediaGallery = false }
                )
            }
            if (showTimerDialog) {
                val timerOptions = listOf(0L to stringResource(R.string.timer_off), 30_000L to stringResource(R.string.timer_30s), 300_000L to stringResource(R.string.timer_5m), 3_600_000L to stringResource(R.string.timer_1h), 86_400_000L to stringResource(R.string.timer_24h), 604_800_000L to stringResource(R.string.timer_7d))
                AlertDialog(
                    onDismissRequest = { showTimerDialog = false },
                    title = { Text(stringResource(R.string.timer_title)) },
                    text = {
                        Column {
                            timerOptions.forEach { (ms, label) ->
                                TextButton(onClick = {
                                    disappearingTimerMs = ms
                                    showTimerDialog = false
                                    if (ms > 0L) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.timer_set, formatTimerShort(ms))
                                            )
                                        }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(label, fontWeight = if (ms == disappearingTimerMs) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showTimerDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
                )
            }
        }
    }
}
}





