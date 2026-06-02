package com.messenger.crisix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.messenger.crisix.ui.theme.NavySurface
import androidx.compose.ui.input.pointer.pointerInput
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
    replyTarget: com.messenger.crisix.ui.screens.Message? = null,
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

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSec = 0
            while (true) {
                delay(1000)
                recordingSec++
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (replyTarget != null) {
            val replyColor = if (replyTarget.isFromMe) {
                NavySurface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
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
                        text = replyTarget.replyToSender ?: if (replyTarget.isFromMe) "Du" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = (replyTarget.text.ifBlank {
                            if (replyTarget.imageUri != null) "📷 Bild" else "🎤 Sprachnachricht"
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
            if (isRecording) {
                // ============================================================
                // RECORDING-MODUS (WhatsApp-Stil)
                // ============================================================

                // Cancel-Button (links) — Tap zum Verwerfen
                IconButton(
                    onClick = onVoiceCancel,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                        tint = NavyError,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Recording-Indikator + Timer (Mitte)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pulsierender roter Punkt
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
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Mikrofon-Icon (rein visuell, rot hinterlegt)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NavyError.copy(alpha = 0.15f * pulseAlpha + 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.voice_message),
                        tint = NavyError,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Senden-Button (blau, Tap zum Beenden + Senden)
                IconButton(
                    onClick = onVoiceEnd,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = NavyPrimary,
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
                // ============================================================
                // NORMAL-MODUS (Texteingabe)
                // ============================================================
                // WICHTIG: Wenn E2EE nicht aktiv ist, wird das gesamte Eingabefeld
                // deaktiviert. Der Benutzer kann erst Nachrichten senden, wenn der
                // Schlüsselaustausch abgeschlossen ist (isE2eeEnabled == true).
                // ============================================================

                // Anhang-Button (links)
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

                // Texteingabe
                TextField(
                    value = messageText,
                    onValueChange = { newText ->
                        if (newText.length <= maxLength) {
                            onMessageChange(newText)
                        }
                    },
                    enabled = isE2eeEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp)),
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

                // Zeichenzähler
                AnimatedVisibility(visible = showCharCounter) {
                    Text(
                        text = "${messageText.length}/$maxLength",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isTextValid) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Mikrofon-Button (WhatsApp-Stil: Tap zum Starten, nicht Long-Press)
                if (capabilities.supportsAudio) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { if (isE2eeEnabled) onVoiceStart() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mic),
                            contentDescription = stringResource(R.string.voice_message),
                            tint = if (isE2eeEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Senden-Button
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
