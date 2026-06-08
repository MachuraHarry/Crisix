package com.messenger.crisix.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.messenger.crisix.R
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiRole
import com.messenger.crisix.ui.theme.NavyChatBubbleOther
import com.messenger.crisix.ui.theme.NavyChatBubbleSelf
import com.messenger.crisix.ui.viewmodel.AiChatViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AiChatDetailScreen(
    conversationId: String,
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiChatViewModel,
) {
    val detailState by viewModel.getDetailState(conversationId).collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendImage(conversationId, it, detailState.inputText) }
    }

    LaunchedEffect(detailState.messages.size, detailState.streamingText) {
        if (detailState.messages.isNotEmpty() || detailState.streamingText.isNotBlank()) {
            val target = detailState.messages.size + if (detailState.isProcessing) 1 else 0
            listState.animateScrollToItem(maxOf(0, target))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ai),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_chat_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back_button),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = !detailState.isProcessing
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_attach),
                            contentDescription = stringResource(R.string.ai_image_pick),
                            tint = if (!detailState.isProcessing)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    TextField(
                        value = detailState.inputText,
                        onValueChange = { viewModel.onInputChange(conversationId, it) },
                        placeholder = {
                            Text(
                                stringResource(R.string.ai_input_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        singleLine = true,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    )

                    if (detailState.isProcessing) {
                        IconButton(onClick = {
                            viewModel.cancelResponse(conversationId)
                            viewModel.stopPrediction()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(R.string.ai_stop_button),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.sendMessage(conversationId) },
                            enabled = detailState.inputText.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_send),
                                contentDescription = stringResource(R.string.ai_send_button),
                                tint = if (detailState.inputText.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (detailState.messages.isEmpty() && !detailState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_ai),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.ai_chat_ask_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                items(detailState.messages, key = { it.id }) { message ->
                    AiDetailMessageBubble(
                        message = message,
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AI message", message.text))
                            Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            viewModel.deleteMessage(conversationId, message.id)
                        },
                    )
                }
                if (detailState.isProcessing && detailState.streamingText.isNotBlank()) {
                    item(key = "streaming") {
                        AiDetailMessageBubble(
                            message = AiMessage(
                                id = "streaming",
                                role = AiRole.ASSISTANT,
                                text = detailState.streamingText,
                            ),
                            onCopy = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AI message", detailState.streamingText))
                                Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {},
                        )
                    }
                } else if (detailState.isProcessing) {
                    item(key = "typing") {
                        DetailTypingIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiDetailMessageBubble(
    message: AiMessage,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val isUser = message.role == AiRole.USER
    val bubbleColor = if (isUser) NavyChatBubbleSelf else NavyChatBubbleOther
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
    )
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_ai),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.ai_chat_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    if (!message.imageUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = Uri.parse(message.imageUri),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (isUser) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_message_copy)) },
                    onClick = {
                        showMenu = false
                        onCopy()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_message_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DetailTypingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ai_typing_indicator),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
