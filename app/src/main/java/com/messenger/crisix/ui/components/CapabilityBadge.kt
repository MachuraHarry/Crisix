package com.messenger.crisix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.messenger.crisix.transport.TransportCapabilities
import com.messenger.crisix.transport.TransportType

/**
 * Zeigt einen Hinweis an, wenn der aktive Transport eingeschränkt ist.
 * Z.B. "Nur Text – Bilder deaktiviert (BLE-Modus)"
 */
@Composable
fun CapabilityBadge(
    transportType: TransportType?,
    capabilities: TransportCapabilities,
    modifier: Modifier = Modifier
) {
    if (transportType == null) return

    val isRestricted = !capabilities.supportsImages &&
            !capabilities.supportsVideo &&
            !capabilities.supportsAudio

    if (!isRestricted) return

    val restrictionText = buildString {
        append("Nur Text")
        if (!capabilities.supportsImages) append(" – Bilder deaktiviert")
        if (!capabilities.supportsVideo) append(" – Video deaktiviert")
        if (!capabilities.supportsAudio) append(" – Audio deaktiviert")
        append(" (${transportType.name})")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = restrictionText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
