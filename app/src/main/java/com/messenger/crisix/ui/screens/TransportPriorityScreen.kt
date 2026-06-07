package com.messenger.crisix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.SettingsSectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportPriorityScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val savedOrder by vm.transportOrder.collectAsState()

    val defaultOrder = remember {
        TransportType.entries.map { it.name }
    }

    val orderedTypes = remember(savedOrder, defaultOrder) {
        if (savedOrder.isNotBlank()) {
            val names = savedOrder.split(",")
            val custom = names.mapNotNull { name ->
                TransportType.entries.find { it.name == name }
            }
            val remaining = TransportType.entries.filter { it.name !in names }
            custom + remaining
        } else {
            TransportType.entries.toList()
        }
    }

    val items = remember { mutableStateListOf<TransportType>().also { it.addAll(orderedTypes) } }

    fun moveUp(index: Int) {
        if (index <= 0) return
        val item = items.removeAt(index)
        items.add(index - 1, item)
        vm.setTransportOrder(items.joinToString(",") { it.name })
    }

    fun moveDown(index: Int) {
        if (index >= items.lastIndex) return
        val item = items.removeAt(index)
        items.add(index + 1, item)
        vm.setTransportOrder(items.joinToString(",") { it.name })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.transport_priority_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionTitle(title = stringResource(R.string.transport_priority_title))

            Text(
                text = stringResource(R.string.transport_priority_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            items.forEachIndexed { index, type ->
                val (iconRes, label, description) = transportInfo(type)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = "$label",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "#${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { moveUp(index) },
                        enabled = index > 0
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.transport_move_up),
                            modifier = Modifier.size(20.dp),
                            tint = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }

                    IconButton(
                        onClick = { moveDown(index) },
                        enabled = index < items.lastIndex
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.transport_move_down),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = 180f },
                            tint = if (index < items.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }

                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun transportInfo(type: TransportType): Triple<Int, String, String> {
    return when (type) {
        TransportType.RELAY -> Triple(
            R.drawable.ic_relay,
            stringResource(R.string.transport_relay_label),
            stringResource(R.string.transport_relay_desc)
        )
        TransportType.INTERNET -> Triple(
            R.drawable.ic_globe,
            stringResource(R.string.transport_internet_label),
            stringResource(R.string.transport_internet_desc)
        )
        TransportType.WIFI_AWARE -> Triple(
            R.drawable.ic_wifi,
            stringResource(R.string.transport_wifi_aware_label),
            stringResource(R.string.transport_wifi_aware_desc)
        )
        TransportType.WIFI_DIRECT -> Triple(
            R.drawable.ic_wifi,
            stringResource(R.string.transport_wifi_label),
            stringResource(R.string.transport_wifi_desc)
        )
        TransportType.BLUETOOTH_MESH -> Triple(
            R.drawable.ic_bluetooth,
            stringResource(R.string.transport_ble_label),
            stringResource(R.string.transport_ble_desc)
        )
        TransportType.SMS -> Triple(
            R.drawable.ic_sms,
            stringResource(R.string.transport_sms_label),
            stringResource(R.string.transport_sms_desc)
        )
        TransportType.DNS_TUNNEL -> Triple(
            R.drawable.ic_dns,
            stringResource(R.string.transport_dns_label),
            stringResource(R.string.transport_dns_desc)
        )
        TransportType.LORA -> Triple(
            R.drawable.ic_lora,
            stringResource(R.string.transport_lora_label),
            stringResource(R.string.transport_lora_desc)
        )
    }
}
