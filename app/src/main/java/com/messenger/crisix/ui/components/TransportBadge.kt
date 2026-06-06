package com.messenger.crisix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.messenger.crisix.R
import com.messenger.crisix.crypto.E2eeSessionState
import com.messenger.crisix.transport.TransportType

@Composable
fun TransportBadge(
    activeTransport: TransportType?,
    isFallback: Boolean,
    sessionState: E2eeSessionState,
    modifier: Modifier = Modifier
) {
    if (activeTransport == null) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TransportDot(activeTransport)
        Column {
            val template = if (isFallback) R.string.transport_badge_fallback else R.string.transport_badge_active
            Text(
                text = stringResource(template, activeTransport.name),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = sessionStateLabel(sessionState),
                style = MaterialTheme.typography.labelSmall,
                color = sessionStateColor(sessionState)
            )
        }
    }
}

@Composable
private fun TransportDot(type: TransportType) {
    val color = when (type) {
        TransportType.WIFI_DIRECT -> Color(0xFF4CAF50)
        TransportType.BLUETOOTH_MESH -> Color(0xFF2196F3)
        TransportType.INTERNET -> Color(0xFF9C27B0)
        TransportType.RELAY -> Color(0xFFFF9800)
        TransportType.DNS_TUNNEL -> Color(0xFF607D8B)
        else -> Color.Gray
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun sessionStateLabel(state: E2eeSessionState): String = when (state) {
    E2eeSessionState.ACTIVE -> stringResource(R.string.session_state_active)
    E2eeSessionState.STALE -> stringResource(R.string.session_state_stale)
    E2eeSessionState.HANDSHAKING -> stringResource(R.string.session_state_handshaking)
    E2eeSessionState.COMPROMISED -> stringResource(R.string.session_state_compromised)
    E2eeSessionState.NONE -> stringResource(R.string.session_state_none)
}

private fun sessionStateColor(state: E2eeSessionState): Color = when (state) {
    E2eeSessionState.ACTIVE -> Color(0xFF4CAF50)
    E2eeSessionState.STALE -> Color(0xFFFFA000)
    E2eeSessionState.HANDSHAKING -> Color(0xFF2196F3)
    E2eeSessionState.COMPROMISED -> Color(0xFFF44336)
    E2eeSessionState.NONE -> Color.Gray
}
