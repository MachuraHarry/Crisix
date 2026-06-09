package com.messenger.crisix.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiRole
import com.messenger.crisix.ui.theme.NavyChatBubbleOther
import com.messenger.crisix.ui.theme.NavyChatBubbleSelf
import com.messenger.crisix.ui.viewmodel.AiChatViewModel
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
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

    var contextSize by remember { mutableIntStateOf(4096) }
    LaunchedEffect(Unit) {
        contextSize = viewModel.getModelManager().getSavedContextSize()
    }

    val totalChars by remember(detailState.messages, detailState.streamingText) {
        derivedStateOf {
            var chars = 0
            for (msg in detailState.messages) chars += msg.text.length
            chars += detailState.streamingText.length
            chars
        }
    }
    val estimatedTokens = totalChars / 2

    LaunchedEffect(detailState.messages.size, detailState.streamingText) {
        if (detailState.messages.isNotEmpty() || detailState.streamingText.isNotBlank()) {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = layoutInfo.totalItemsCount
            if (total == 0 || lastVisible >= total - 3) {
                val target = detailState.messages.size + if (detailState.isProcessing) 1 else 0
                listState.animateScrollToItem(maxOf(0, target))
            }
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
                actions = {
                    Text(
                        text = "~${estimatedTokens} / ${contextSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 12.dp),
                    )
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
                } else if (detailState.isProcessing && detailState.toolStatus.isNotBlank()) {
                    item(key = "tool_status") {
                        DetailToolStatusIndicator(status = detailState.toolStatus)
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
    val isStreaming = message.id == "streaming"
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
                    if (isUser) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        val rawText = message.text
                        if (isStreaming) {
                            StreamingMarkdownText(rawText = rawText)
                        } else {
                            val markdownState = rememberMarkdownState(content = rawText)
                            Markdown(
                                markdownState = markdownState,
                                modifier = Modifier.fillMaxWidth(),
                                colors = markdownColor(
                                    codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                ),
                                typography = markdownTypography(
                                    h1 = MaterialTheme.typography.headlineSmall,
                                    h2 = MaterialTheme.typography.titleLarge,
                                    h3 = MaterialTheme.typography.titleMedium,
                                    h4 = MaterialTheme.typography.titleSmall,
                                    h5 = MaterialTheme.typography.bodyLarge,
                                    h6 = MaterialTheme.typography.bodyMedium,
                                    text = MaterialTheme.typography.bodyMedium,
                                    code = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    list = MaterialTheme.typography.bodyMedium,
                                    quote = MaterialTheme.typography.bodyMedium.copy(
                                        textDecoration = TextDecoration.None,
                                    ),
                                ),
                            )
                        }
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
private fun StreamingMarkdownText(rawText: String) {
    var displayText by remember { mutableStateOf(rawText) }
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(rawText) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateTime
        if (elapsed < 150) {
            delay(150 - elapsed)
        }
        displayText = rawText
        lastUpdateTime = System.currentTimeMillis()
    }

    val style = MaterialTheme.typography.bodyMedium
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val annotatedString = remember(displayText) {
        val flavour = GFMFlavourDescriptor()
        val parser = MarkdownParser(flavour)
        val parsedTree = parser.buildMarkdownTreeFromString(displayText)
        val settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(),
            codeSpanStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBg,
            ),
            annotator = markdownAnnotator(),
        )
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            var first = true
            for (child in parsedTree.children) {
                if (!first) append('\n')
                first = false
                if (!appendStreamBlock(displayText, child, settings, style)) {
                    buildMarkdownAnnotatedString(displayText, child, settings)
                }
            }
            pop()
        }
    }
    Text(
        text = annotatedString,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun AnnotatedString.Builder.appendStreamBlock(
    content: String,
    node: ASTNode,
    settings: AnnotatorSettings,
    style: TextStyle,
): Boolean = when (node.type) {
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> {
        val scale = when (node.type) {
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1.5f
            MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 1.3f
            MarkdownElementTypes.ATX_3 -> 1.15f
            else -> 1.0f
        }
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = style.fontSize * scale))
        buildMarkdownAnnotatedString(content, node, settings)
        pop()
        true
    }
    MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> {
        pushStyle(settings.codeSpanStyle)
        val codeText = node.children
            .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            .joinToString("") { it.getTextInNode(content).toString() }
        if (codeText.isNotBlank()) append(codeText)
        pop()
        true
    }
    MarkdownElementTypes.UNORDERED_LIST -> {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        for ((i, item) in items.withIndex()) {
            if (i > 0) append('\n')
            append("• ")
            buildMarkdownAnnotatedString(content, item, settings)
        }
        true
    }
    MarkdownElementTypes.ORDERED_LIST -> {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        for ((i, item) in items.withIndex()) {
            if (i > 0) append('\n')
            append("${i + 1}. ")
            buildMarkdownAnnotatedString(content, item, settings)
        }
        true
    }
    MarkdownElementTypes.BLOCK_QUOTE -> {
        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        buildMarkdownAnnotatedString(content, node, settings)
        pop()
        true
    }
    else -> false
}

@Composable
private fun DetailTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dotCount = 3

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(dotCount) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
            if (i < dotCount - 1) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ai_typing_indicator),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailToolStatusIndicator(status: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "tool_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool_alpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
