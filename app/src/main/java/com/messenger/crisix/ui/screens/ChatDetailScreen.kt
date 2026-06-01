package com.messenger.crisix.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import java.util.Calendar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.messenger.crisix.R
import com.messenger.crisix.data.MessageEntity
import com.messenger.crisix.data.toMessage
import com.messenger.crisix.transport.DeliveryUpdate
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportCapabilities
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.AdaptiveInputBar
import com.messenger.crisix.ui.components.AudioBubble
import com.messenger.crisix.ui.components.CapabilityBadge
import com.messenger.crisix.ui.components.ImagePreviewDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class HintStatus {
    LOADING, SUCCESS, FAILURE
}

data class Message(
    val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val status: MessageStatus = if (isFromMe) MessageStatus.SENDING else MessageStatus.DELIVERED,
    val transport: TransportType? = null,
    val imageUri: String? = null,
    val audioUri: String? = null,
    val audioDurationMs: Long = 0L,
    val isEncrypted: Boolean = false,
    val isRead: Boolean = false,
    val isSystemMessage: Boolean = false,
    val hintStatus: HintStatus? = null,
)

private val URL_PATTERN = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")

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
    onSendMessage: (String) -> Unit,
    onSendImage: ((Uri) -> Unit)? = null,
    onSendVoice: ((ByteArray, Long) -> Unit)? = null,
    isE2eeEnabled: Boolean = false,
    onMarkChatAsRead: (() -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
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

    val lazyEntities = messagesFlow.collectAsLazyPagingItems()

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

    if (previewImageUri != null) {
        ImagePreviewDialog(
            imageUri = previewImageUri!!,
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
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
                            onSendMessage(messageText)
                            messageText = ""
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
                    isE2eeEnabled = isE2eeEnabled
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
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
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
                                onImageClick = { uri ->
                                    previewImageUri = uri
                                },
                            )
                }
            }
        }
            }
        }
    }
}

private fun getDateGroup(timestampMillis: Long): Int {
    if (timestampMillis == 0L) return 4
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return when {
        now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
            && now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR) -> 0
        else -> {
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (yesterday.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)
            ) 1
            else if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
                && now.get(Calendar.WEEK_OF_YEAR) == msgTime.get(Calendar.WEEK_OF_YEAR)
            ) 2
            else 3
        }
    }
}

@Composable
private fun dateLabel(timestampMillis: Long): String {
    return when (getDateGroup(timestampMillis)) {
        0 -> stringResource(R.string.date_today)
        1 -> stringResource(R.string.date_yesterday)
        2 -> stringResource(R.string.date_this_week)
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
            java.text.SimpleDateFormat("d. MMMM", java.util.Locale.getDefault()).format(cal.time)
        }
    }
}

private fun startRecording(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    val audioDir = File(context.filesDir, "audio")
    audioDir.mkdirs()
    scope.launch {
        try {
            com.messenger.crisix.util.AudioRecorder.startRecording(context, audioDir)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Fehler: Mikrofon nicht verfügbar")
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    context: Context,
    chatId: String,
    incomingTransport: TransportType?,
    showMetadata: Boolean = true,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onImageClick: (String) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    if (message.isSystemMessage) {
        val hintColor = when (message.hintStatus) {
            HintStatus.SUCCESS -> Color(0xFF74b562)
            HintStatus.FAILURE -> Color(0xFFb56262)
            HintStatus.LOADING -> Color(0xFFe0a000)
            null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = hintColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        return
    }

    val bubbleColor = if (message.isFromMe) {
        Color(0xFF1B2A4A)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (message.isFromMe) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val effectiveTransport = message.transport ?: if (!message.isFromMe) incomingTransport else null

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (message.isFromMe) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (message.imageUri != null) {
                AsyncImage(
                    model = message.imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(message.imageUri) },
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (message.audioUri != null) {
                AudioBubble(
                    audioUri = message.audioUri,
                    durationMs = message.audioDurationMs,
                    isFromMe = message.isFromMe,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (message.text.isNotBlank()) {
                val linkContext = LocalContext.current
                val linkColor = MaterialTheme.colorScheme.tertiary
                val hasUrls = URL_PATTERN.containsMatchIn(message.text)
                if (hasUrls) {
                    val annotated = remember(message.text, linkColor) {
                        buildAnnotatedString {
                            val matches = URL_PATTERN.findAll(message.text)
                            var lastEnd = 0
                            for (match in matches) {
                                if (match.range.first > lastEnd) {
                                    append(message.text.substring(lastEnd, match.range.first))
                                }
                                pushStringAnnotation(tag = "URL", annotation = match.value)
                                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                    append(match.value)
                                }
                                pop()
                                lastEnd = match.range.last + 1
                            }
                            if (lastEnd < message.text.length) {
                                append(message.text.substring(lastEnd))
                            }
                        }
                    }
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        onClick = { offset ->
                            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                                    linkContext.startActivity(intent)
                                }
                        }
                    )
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            }
            if (showMetadata) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEncrypted) {
                        Text(
                            text = "\uD83D\uDD12",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (effectiveTransport != null) {
                        Text(
                            text = stringResource(R.string.chat_detail_via, transportLabel(effectiveTransport)),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (message.isFromMe) {
                        StatusIcon(status = message.status, textColor = textColor, isRead = message.isRead)
                    }
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy)) },
                onClick = {
                    showMenu = false
                    onCopy()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    showMenu = false
                    onDelete?.invoke()
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
}

@Composable
private fun StatusIcon(status: MessageStatus, textColor: Color, isRead: Boolean = false) {
    val readColor = MaterialTheme.colorScheme.primary
    val (icon, color) = when (status) {
        MessageStatus.SENDING -> "⏳" to textColor.copy(alpha = 0.5f)
        MessageStatus.PENDING -> "⏳" to textColor.copy(alpha = 0.5f)
        MessageStatus.SENT -> "✓" to textColor.copy(alpha = 0.5f)
        MessageStatus.DELIVERED -> "✓✓" to if (isRead) readColor else textColor.copy(alpha = 0.7f)
        MessageStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
    }
    Text(
        text = icon,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Composable
private fun transportLabel(type: TransportType): String = when (type) {
    TransportType.INTERNET -> stringResource(R.string.transport_dht)
    TransportType.RELAY -> stringResource(R.string.transport_relay)
    TransportType.DNS_TUNNEL -> stringResource(R.string.transport_dns)
    TransportType.WIFI_DIRECT -> stringResource(R.string.transport_wlan)
    TransportType.BLUETOOTH_MESH -> stringResource(R.string.transport_ble)
    TransportType.SMS -> stringResource(R.string.transport_sms)
    TransportType.LORA -> stringResource(R.string.transport_lora)
}
