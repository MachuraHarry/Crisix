package com.messenger.crisix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportCapabilities

/**
 * Adaptive Eingabeleiste im WhatsApp-Stil.
 * Passt sich dynamisch an die Capabilities des aktuellen Transportwegs an.
 *
 * Layout (wie WhatsApp):
 * [Anhang] [    Texteingabe (abgerundet)    ] [Mikro] [Senden]
 */
@Composable
fun AdaptiveInputBar(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onVoiceClick: () -> Unit,
    capabilities: TransportCapabilities,
    modifier: Modifier = Modifier
) {
    val maxLength = capabilities.maxTextLength
    val isTextValid = messageText.length <= maxLength
    val showCharCounter = maxLength < Int.MAX_VALUE
    val supportsMedia = capabilities.supportsImages ||
            capabilities.supportsVideo ||
            capabilities.supportsFileTransfer

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Anhang-Button (WhatsApp: links)
        IconButton(
            onClick = { if (supportsMedia) onAttachClick() },
            enabled = supportsMedia,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_attach),
                contentDescription = if (supportsMedia) stringResource(R.string.attach_button) else stringResource(R.string.attach_disabled),
                tint = if (supportsMedia) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }

        // Texteingabe (WhatsApp-Stil: abgerundet, kein Outline)
        TextField(
            value = messageText,
            onValueChange = { newText ->
                if (newText.length <= maxLength) {
                    onMessageChange(newText)
                }
            },
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp)),
            placeholder = {
                Text(
                    stringResource(R.string.input_placeholder),
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
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (isTextValid && messageText.isNotBlank()) {
                        onSend()
                    }
                }
            ),
            singleLine = true,
            maxLines = 1
        )

        // Zeichenzähler (nur bei limitierten Transporten)
        AnimatedVisibility(visible = showCharCounter) {
            Text(
                text = "${messageText.length}/$maxLength",
                style = MaterialTheme.typography.labelSmall,
                color = if (isTextValid) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Mikrofon-Button (WhatsApp: rechts neben Eingabe, links vom Senden)
        if (capabilities.supportsAudio) {
            IconButton(
                onClick = onVoiceClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic),
                    contentDescription = stringResource(R.string.voice_message),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Senden-Button (WhatsApp: grüner Kreis)
        IconButton(
            onClick = {
                if (isTextValid && messageText.isNotBlank()) {
                    onSend()
                }
            },
            enabled = isTextValid && messageText.isNotBlank(),
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isTextValid && messageText.isNotBlank())
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isTextValid && messageText.isNotBlank())
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
