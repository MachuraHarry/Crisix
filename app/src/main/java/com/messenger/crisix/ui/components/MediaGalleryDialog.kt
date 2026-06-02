package com.messenger.crisix.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.messenger.crisix.R
import com.messenger.crisix.util.AudioPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryDialog(
    chatName: String,
    mediaItems: List<Message>,
    onDismiss: () -> Unit,
    onImageClick: (String) -> Unit,
    onAudioClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.media_gallery_title, chatName)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            if (mediaItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.media_gallery_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(mediaItems, key = { it.id }) { msg ->
                        MediaGridItem(
                            msg = msg,
                            chatName = chatName,
                            onImageClick = onImageClick,
                            onAudioClick = { uri ->
                                val ctx = context
                                AudioPlayer.playFrom(ctx, Uri.parse(uri), 0L) {}
                                onAudioClick?.invoke(uri)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGridItem(
    msg: Message,
    chatName: String,
    onImageClick: (String) -> Unit,
    onAudioClick: (String) -> Unit,
) {
    val timeText = formatMediaTimestamp(msg.timestampMillis)
    val senderLabel = if (msg.isFromMe) {
        stringResource(R.string.media_sender_me)
    } else {
        val name = chatName.ifBlank { msg.id.take(8) }
        if (name.length > 12) name.take(12) else name
    }

    Card(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .clickable {
                if (msg.imageUri != null) onImageClick(msg.imageUri)
                else if (msg.audioUri != null) onAudioClick(msg.audioUri)
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (msg.imageUri != null) {
                    AsyncImage(
                        model = Uri.parse(msg.imageUri),
                        contentDescription = stringResource(R.string.image_preview_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (msg.audioUri != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mic),
                            contentDescription = stringResource(R.string.audio_play_pause),
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = formatDuration(msg.audioDurationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = Color.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatMediaTimestamp(timestampMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val now = java.util.Calendar.getInstance()
    val sdf = if (cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    } else {
        java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.getDefault())
    }
    return sdf.format(cal.time)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return if (minutes > 0) "${minutes}:${secs.toString().padStart(2, '0')}"
    else "0:${secs.toString().padStart(2, '0')}"
}
