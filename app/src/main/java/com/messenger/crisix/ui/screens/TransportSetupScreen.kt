package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.messenger.crisix.ui.theme.NavyAccent
import com.messenger.crisix.ui.theme.NavyAccentDark
import com.messenger.crisix.ui.theme.NavyOnDarkMuted
import com.messenger.crisix.ui.theme.NavyPrimary
import com.messenger.crisix.ui.theme.NavySecondary
import com.messenger.crisix.ui.theme.NavySurfaceVariant
import com.messenger.crisix.ui.theme.NavyWarning
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportType

@Composable
fun TransportSetupScreen(
    transportSettings: Map<TransportType, Boolean>,
    onTransportToggle: (TransportType, Boolean) -> Unit,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.transport_setup_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.transport_setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            transportSettings.forEach { (type, enabled) ->
                TransportSetupItem(
                    transportType = type,
                    enabled = enabled,
                    onToggle = { onTransportToggle(type, it) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = stringResource(R.string.transport_setup_done),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TransportSetupItem(
    transportType: TransportType,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val (label, description, color) = transportInfo(transportType)
    val isComingSoon = transportType == TransportType.LORA

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description + if (isComingSoon) " (Coming Soon)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = if (isComingSoon) null else onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun transportInfo(type: TransportType): Triple<String, String, Color> {
    return when (type) {
        TransportType.RELAY -> Triple(
            stringResource(R.string.transport_relay_label),
            stringResource(R.string.transport_relay_desc),
            NavyPrimary
        )
        TransportType.INTERNET -> Triple(
            stringResource(R.string.transport_internet_label),
            stringResource(R.string.transport_internet_desc),
            NavyAccent
        )
        TransportType.WIFI_AWARE -> Triple(
            stringResource(R.string.transport_wifi_aware_label),
            stringResource(R.string.transport_wifi_aware_desc),
            NavySecondary
        )
        TransportType.WIFI_DIRECT -> Triple(
            stringResource(R.string.transport_wifi_label),
            stringResource(R.string.transport_wifi_desc),
            NavySecondary
        )
        TransportType.BLUETOOTH_MESH -> Triple(
            stringResource(R.string.transport_ble_label),
            stringResource(R.string.transport_ble_desc),
            NavyAccentDark
        )
        TransportType.SMS -> Triple(
            stringResource(R.string.transport_sms_label),
            stringResource(R.string.transport_sms_desc),
            NavyWarning
        )
        TransportType.DNS_TUNNEL -> Triple(
            stringResource(R.string.transport_dns_label),
            stringResource(R.string.transport_dns_desc),
            NavySurfaceVariant
        )
        TransportType.LORA -> Triple(
            stringResource(R.string.transport_lora_label),
            stringResource(R.string.transport_lora_desc),
            NavyOnDarkMuted
        )
    }
}
