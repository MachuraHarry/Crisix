package com.messenger.crisix.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.messenger.crisix.R
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.theme.NavyChatBubbleOther
import com.messenger.crisix.ui.theme.NavyChatBubbleSelf
import com.messenger.crisix.ui.theme.NavyError
import com.messenger.crisix.ui.theme.NavyPrimary
import com.messenger.crisix.ui.theme.NavyWarning
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch

enum class HintStatus {
    LOADING, SUCCESS, FAILURE
}

@Immutable
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
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val disappearingTimerMs: Long = 0L,
    val dateGroupOrdinal: Int = -1,
    val olderDateLabel: String? = null,
)

@Composable
fun MessageBubble(
    message: Message,
    context: Context,
    chatId: String,
    incomingTransport: TransportType?,
    showMetadata: Boolean = true,
    bubbleStyle: String = "standard",
    onCopy: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onImageClick: (String) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    if (message.isSystemMessage) {
        val hintColor = when (message.hintStatus) {
            HintStatus.SUCCESS -> NavyPrimary
            HintStatus.FAILURE -> NavyError
            HintStatus.LOADING -> NavyWarning
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
        NavyChatBubbleSelf
    } else {
        NavyChatBubbleOther
    }

    val textColor = if (message.isFromMe) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val bubbleShape = if (bubbleStyle == "compact") {
        RoundedCornerShape(
            topStart = 10.dp,
            topEnd = 10.dp,
            bottomStart = if (message.isFromMe) 10.dp else 4.dp,
            bottomEnd = if (message.isFromMe) 4.dp else 10.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (message.isFromMe) 16.dp else 4.dp,
            bottomEnd = if (message.isFromMe) 4.dp else 16.dp,
        )
    }

    val bubblePadding = if (bubbleStyle == "compact") {
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
    } else {
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
    }

    val effectiveTransport = message.transport ?: if (!message.isFromMe) incomingTransport else null

    var dragTarget by remember { mutableFloatStateOf(0f) }
    val swipeOffset by animateFloatAsState(dragTarget, spring())
    val swipeThreshold = 80f
    var hasTriggeredReply by remember { mutableStateOf(false) }
    val replyColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val maxBubbleWidth = if (message.audioUri != null) maxWidth * 0.60f else maxWidth * 0.95f

        if (onReply != null && swipeOffset > 0f) {
            val indicatorAlign = if (message.isFromMe) Alignment.CenterStart else Alignment.CenterEnd
            val indicatorPadding = if (message.isFromMe) Modifier.padding(start = 20.dp) else Modifier.padding(end = 20.dp)
            Row(
                modifier = Modifier
                    .align(indicatorAlign)
                    .then(indicatorPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_reply),
                    contentDescription = stringResource(R.string.action_reply),
                    tint = replyColor.copy(alpha = (swipeOffset / swipeThreshold).coerceIn(0f, 1f)),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_reply),
                    style = MaterialTheme.typography.labelMedium,
                    color = replyColor.copy(alpha = (swipeOffset / swipeThreshold).coerceIn(0f, 1f))
                )
            }
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = swipeOffset }
            .padding(vertical = 2.dp),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(
                    min = if (message.audioUri != null) maxBubbleWidth else 48.dp,
                    max = maxBubbleWidth
                )
                .clip(bubbleShape)
                .background(bubbleColor)
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true },
                )
                .pointerInput(onReply) {
                    if (onReply != null) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragTarget >= swipeThreshold && !hasTriggeredReply) {
                                    hasTriggeredReply = true
                                    onReply.invoke()
                                }
                                dragTarget = 0f
                            },
                            onDragCancel = {
                                dragTarget = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                dragTarget = (dragTarget + dragAmount * 0.5f)
                                    .coerceIn(0f, swipeThreshold * 1.5f)
                            }
                        )
                    }
                }
                .then(bubblePadding)
        ) {
            if (message.replyToId != null) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = message.replyToSender ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = (message.replyToText ?: "").take(80),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (message.imageUri != null) {
                val density = LocalDensity.current
                val imageWidthPx = with(density) {
                    val availableWidth = 280.dp - 14.dp - 14.dp
                    availableWidth.toPx().roundToInt()
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(message.imageUri)
                        .size(imageWidthPx)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)
                        )
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
                Markdown(
                    content = message.text,
                    colors = markdownColor(text = textColor),
                    typography = markdownTypography(
                        text = MaterialTheme.typography.bodyMedium,
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleMedium,
                        h3 = MaterialTheme.typography.titleSmall,
                        h4 = MaterialTheme.typography.bodyLarge,
                        h5 = MaterialTheme.typography.bodyMedium,
                        h6 = MaterialTheme.typography.bodySmall
                    ),
                    modifier = Modifier
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
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
                        text = stringResource(R.string.chat_detail_via, transportShortLabel(effectiveTransport)),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                if (showMetadata) {
                    if (message.isFromMe) {
                        StatusIcon(status = message.status, textColor = textColor)
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
            if (onReply != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_reply)) },
                    onClick = {
                        showMenu = false
                        onReply()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_reply),
                    contentDescription = stringResource(R.string.message_image_description),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
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
private fun StatusIcon(status: MessageStatus, textColor: Color) {
    val readColor = MaterialTheme.colorScheme.primary
    val (icon, color) = when (status) {
        MessageStatus.SENDING -> "⏳" to textColor.copy(alpha = 0.5f)
        MessageStatus.PENDING -> "⏳" to textColor.copy(alpha = 0.5f)
        MessageStatus.SENT -> "✓" to textColor.copy(alpha = 0.5f)
        MessageStatus.DELIVERED -> "✓✓" to textColor.copy(alpha = 0.7f)
        MessageStatus.READ -> "✓✓" to readColor
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
internal fun transportShortLabel(type: TransportType): String = when (type) {
    TransportType.INTERNET -> stringResource(R.string.transport_dht)
    TransportType.RELAY -> stringResource(R.string.transport_relay)
    TransportType.DNS_TUNNEL -> stringResource(R.string.transport_dns)
    TransportType.WIFI_AWARE -> stringResource(R.string.transport_wlan)
    TransportType.WIFI_DIRECT -> stringResource(R.string.transport_wlan)
    TransportType.BLUETOOTH_MESH -> stringResource(R.string.transport_ble)
    TransportType.SMS -> stringResource(R.string.transport_sms)
    TransportType.LORA -> stringResource(R.string.transport_lora)
}
