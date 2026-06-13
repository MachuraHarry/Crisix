package com.messenger.crisix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.messenger.crisix.ui.theme.NavyError
import com.messenger.crisix.ui.theme.NavyPrimary
import com.messenger.crisix.ui.theme.NavySecondary
import com.messenger.crisix.ui.theme.NavyStatusPositive
import com.messenger.crisix.ui.theme.NavySurface
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportCapabilities
import kotlinx.coroutines.delay

@Composable
fun AdaptiveInputBar(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit = onVoiceEnd,
    isRecording: Boolean = false,
    capabilities: TransportCapabilities,
    isE2eeEnabled: Boolean = true,
    replyTarget: com.messenger.crisix.ui.components.Message? = null,
    onClearReply: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val maxLength = capabilities.maxTextLength
    val isTextValid = messageText.length <= maxLength
    val showCharCounter = maxLength < Int.MAX_VALUE
    val supportsMedia = capabilities.supportsImages ||
            capabilities.supportsVideo ||
            capabilities.supportsFileTransfer

    var recordingSec by remember { mutableIntStateOf(0) }
    var isLocallyRecording by remember { mutableStateOf(false) }
    var isRecordingLocked by remember { mutableStateOf(false) }
    var willCancel by remember { mutableStateOf(false) }
    var approachingLock by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val showRecording = isLocallyRecording || isRecording || isRecordingLocked

    LaunchedEffect(showRecording) {
        if (showRecording) {
            recordingSec = 0
            while (true) {
                delay(1000)
                recordingSec++
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (replyTarget != null) {
            val replyColor = if (replyTarget.isFromMe) NavySurface
            else MaterialTheme.colorScheme.surfaceVariant
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(replyColor.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = replyTarget.replyToSender ?: if (replyTarget.isFromMe) stringResource(R.string.chat_detail_reply_you) else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = (replyTarget.text.ifBlank {
                            if (replyTarget.imageUri != null) stringResource(R.string.chat_detail_reply_image) else stringResource(R.string.chat_detail_reply_voice)
                        }).take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onClearReply,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!showRecording) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { if (supportsMedia) onAttachClick() },
                        enabled = supportsMedia && isE2eeEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_attach),
                            contentDescription = if (supportsMedia) stringResource(R.string.attach_button) else stringResource(R.string.attach_disabled),
                            tint = if (supportsMedia && isE2eeEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }

                    TextField(
                        value = messageText,
                        onValueChange = { newText ->
                            if (newText.length <= maxLength) {
                                onMessageChange(newText)
                            }
                        },
                        enabled = isE2eeEnabled,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                        placeholder = {
                            Text(
                                if (isE2eeEnabled) stringResource(R.string.input_placeholder)
                                else stringResource(R.string.e2ee_waiting_for_key_exchange),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (isE2eeEnabled && isTextValid && messageText.isNotBlank()) {
                                    onSend()
                                }
                            }
                        ),
                        singleLine = true,
                        maxLines = 1
                    )
                }
            }

            if (showRecording) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isLocallyRecording) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (approachingLock) NavyPrimary.copy(alpha = 0.25f)
                                    else if (willCancel) NavyError.copy(alpha = 0.15f)
                                    else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (approachingLock) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.voice_lock),
                                    tint = NavyPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.cancel),
                                    tint = if (willCancel) NavyError else NavyError.copy(alpha = 0.25f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (isRecordingLocked) {
                        IconButton(
                            onClick = {
                                onVoiceCancel()
                                isRecordingLocked = false
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NavyError),
                            )
                        }
                    }

                    val hintText = when {
                        isRecordingLocked -> ""
                        approachingLock -> stringResource(R.string.voice_release_to_lock)
                        willCancel -> stringResource(R.string.voice_release_to_cancel)
                        isLocallyRecording -> stringResource(R.string.voice_slide_left_to_cancel)
                        else -> ""
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .then(
                                if (isRecordingLocked) Modifier.border(
                                    1.5.dp, NavyPrimary.copy(alpha = 0.4f), RoundedCornerShape(24.dp)
                                ) else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (hintText.isNotBlank()) {
                                Text(
                                    text = hintText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        willCancel -> NavyError
                                        approachingLock -> NavyPrimary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                RecordingWaveform(
                                    isActive = showRecording,
                                    isCancel = willCancel || (!isRecordingLocked && !isLocallyRecording),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.weight(0.01f))

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(NavyError.copy(alpha = pulseAlpha))
                            )

                            Text(
                                text = "%d:%02d".format(recordingSec / 60, recordingSec % 60),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                ),
                                color = if (willCancel) NavyError else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (capabilities.supportsAudio) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isRecordingLocked -> NavyPrimary
                                showRecording -> NavyError.copy(alpha = 0.12f * pulseAlpha + 0.04f)
                                else -> Color.Transparent
                            }
                        )
                        .pointerInput(isE2eeEnabled) {
                            if (!isE2eeEnabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isLocallyRecording = true
                                    isRecordingLocked = false
                                    willCancel = false
                                    approachingLock = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onVoiceStart()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val totalX = dragAmount.x
                                    val totalY = dragAmount.y
                                    willCancel = totalX < -70f
                                    approachingLock = totalY < -70f
                                },
                                onDragEnd = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (approachingLock) {
                                        isRecordingLocked = true
                                        isLocallyRecording = false
                                        approachingLock = false
                                        willCancel = false
                                    } else if (willCancel) {
                                        onVoiceCancel()
                                        isLocallyRecording = false
                                        isRecordingLocked = false
                                        willCancel = false
                                        approachingLock = false
                                    } else {
                                        onVoiceEnd()
                                        isLocallyRecording = false
                                        isRecordingLocked = false
                                        willCancel = false
                                        approachingLock = false
                                    }
                                },
                                onDragCancel = {
                                    onVoiceCancel()
                                    isLocallyRecording = false
                                    isRecordingLocked = false
                                    willCancel = false
                                    approachingLock = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.voice_message),
                        tint = when {
                            !isE2eeEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            isRecordingLocked -> Color.White
                            showRecording -> NavyError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (showRecording && !isLocallyRecording) {
                if (isRecordingLocked) {
                    IconButton(
                        onClick = {
                            onVoiceEnd()
                            isRecordingLocked = false
                        },
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = NavyStatusPositive,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = stringResource(R.string.send_button),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (willCancel) NavyPrimary.copy(alpha = 0.08f)
                                else NavyPrimary.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = stringResource(R.string.send_button),
                            tint = if (willCancel) NavyPrimary.copy(alpha = 0.2f) else NavyStatusPositive,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!showRecording) {
                IconButton(
                    onClick = {
                        if (isE2eeEnabled && isTextValid && messageText.isNotBlank()) {
                            onSend()
                        }
                    },
                    enabled = isE2eeEnabled && isTextValid && messageText.isNotBlank(),
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isE2eeEnabled && isTextValid && messageText.isNotBlank())
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isE2eeEnabled && isTextValid && messageText.isNotBlank())
                            MaterialTheme.colorScheme.onTertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send),
                        contentDescription = stringResource(R.string.send_button),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Live recording waveform (Telegram-style animated bars)
// ═══════════════════════════════════════════════════════════════
@Composable
private fun RecordingWaveform(
    isActive: Boolean,
    isCancel: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 24
    val transition = rememberInfiniteTransition(label = "recordwave")

    Row(
        modifier = modifier.height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(1.8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (index in 0 until barCount) {
            val barH by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 350 + (index * 37),
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wbar$index"
            )
            val squaredHeight = (barH * barH).coerceAtLeast(0.15f)
            val realH = (squaredHeight * 20).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(realH)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        if (isCancel) NavyError.copy(alpha = 0.55f)
                        else NavySecondary.copy(alpha = 0.65f)
                    )
            )
        }
    }
}
