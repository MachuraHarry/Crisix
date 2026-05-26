package com.messenger.crisix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportCapabilities

/**
 * Adaptive Eingabeleiste, die sich dynamisch an die Capabilities
 * des aktuellen Transportwegs anpasst.
 *
 * - Wenn der Transport keine Bilder unterstützt → Anhang-Button ausgegraut
 * - Wenn der Transport kein Audio unterstützt → Mikrofon-Button ausgegraut
 * - Wenn ein Zeichenlimit existiert → Zeichenzähler anzeigen
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Anhang-Button (nur aktiv, wenn Bilder/Video/Dateien unterstützt werden)
        val supportsMedia = capabilities.supportsImages ||
                capabilities.supportsVideo ||
                capabilities.supportsFileTransfer

        IconButton(
            onClick = { if (supportsMedia) onAttachClick() },
            enabled = supportsMedia,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_attach),
                contentDescription = if (supportsMedia) "Anhang" else "Anhang deaktiviert",
                tint = if (supportsMedia) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }

        // Texteingabe
        OutlinedTextField(
            value = messageText,
            onValueChange = { newText ->
                if (newText.length <= maxLength) {
                    onMessageChange(newText)
                }
            },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Nachricht",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (isTextValid && messageText.isNotBlank()) onSend() }),
            singleLine = true,
            maxLines = 1
        )

        // Zeichenzähler (nur bei limitierten Transporten wie SMS, BLE, DNS)
        AnimatedVisibility(visible = showCharCounter) {
            Text(
                text = "${messageText.length}/$maxLength",
                style = MaterialTheme.typography.labelSmall,
                color = if (isTextValid) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Mikrofon-Button (nur aktiv, wenn Audio unterstützt wird)
        IconButton(
            onClick = { if (capabilities.supportsAudio) onVoiceClick() },
            enabled = capabilities.supportsAudio,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic),
                contentDescription = if (capabilities.supportsAudio) "Sprachnachricht"
                else "Sprachnachricht deaktiviert",
                tint = if (capabilities.supportsAudio) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }

        // Senden-Button
        IconButton(
            onClick = {
                if (isTextValid && messageText.isNotBlank()) {
                    onSend()
                }
            },
            enabled = isTextValid && messageText.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isTextValid && messageText.isNotBlank()) {
                        Modifier
                    } else {
                        Modifier
                    }
                )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_send),
                contentDescription = "Senden",
                tint = if (isTextValid && messageText.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}
