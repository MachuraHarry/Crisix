package com.messenger.crisix.ui.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.data.RelayServer
import com.messenger.crisix.ui.components.SettingsSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayServersScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val servers by vm.relayServers.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<RelayServer?>(null) }
    var deletingServer by remember { mutableStateOf<RelayServer?>(null) }
    val testStatuses = remember { mutableStateOf(mapOf<String, String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_relay_servers),
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
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = stringResource(R.string.relay_server_add)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionTitle(title = stringResource(R.string.settings_relay_servers))

            Text(
                text = stringResource(R.string.settings_relay_servers_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.relay_server_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            servers.forEachIndexed { index, server ->
                val testStatus = testStatuses.value[server.id]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )

                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val mutable = servers.toMutableList()
                                        mutable.removeAt(index)
                                        mutable.add(index - 1, server)
                                        vm.reorderRelayServers(mutable)
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(R.string.transport_move_up),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (index > 0) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (index < servers.lastIndex) {
                                        val mutable = servers.toMutableList()
                                        mutable.removeAt(index)
                                        mutable.add(index + 1, server)
                                        vm.reorderRelayServers(mutable)
                                    }
                                },
                                enabled = index < servers.lastIndex
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(R.string.transport_move_down),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer { rotationZ = 180f },
                                    tint = if (index < servers.lastIndex) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        testStatuses.value = testStatuses.value + (server.id to "checking")
                                        val result = testRelayConnection(server.url)
                                        testStatuses.value = testStatuses.value + (server.id to result)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                ),
                                enabled = testStatus != "checking"
                            ) {
                                if (testStatus == "checking") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = when (testStatus) {
                                        "ok" -> stringResource(R.string.relay_server_test_ok)
                                        "fail" -> stringResource(R.string.relay_server_test_fail)
                                        "checking" -> stringResource(R.string.relay_server_test_checking)
                                        else -> stringResource(R.string.relay_server_test)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (testStatus) {
                                        "ok" -> Color(0xFF4CAF50)
                                        "fail" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(onClick = { editingServer = server }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_person),
                                    contentDescription = stringResource(R.string.relay_server_edit),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            IconButton(onClick = { deletingServer = server }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.relay_server_delete),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showAddDialog) {
        RelayServerEditDialog(
            title = stringResource(R.string.relay_server_add),
            initialName = "",
            initialUrl = "",
            onSave = { name, url ->
                vm.addRelayServer(name, url)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    if (editingServer != null) {
        val server = editingServer!!
        RelayServerEditDialog(
            title = stringResource(R.string.relay_server_edit),
            initialName = server.name,
            initialUrl = server.url,
            onSave = { name, url ->
                vm.updateRelayServer(server.id, name, url)
                editingServer = null
            },
            onDismiss = { editingServer = null }
        )
    }

    if (deletingServer != null) {
        val server = deletingServer!!
        AlertDialog(
            onDismissRequest = { deletingServer = null },
            title = {
                Text(
                    stringResource(R.string.relay_server_delete_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.relay_server_delete_text, server.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeRelayServer(server.id)
                    deletingServer = null
                }) {
                    Text(
                        stringResource(R.string.settings_reset_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingServer = null }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }
}

@Composable
private fun RelayServerEditDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(50) },
                    label = { Text(stringResource(R.string.relay_server_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(200) },
                    label = { Text(stringResource(R.string.relay_server_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}

private suspend fun testRelayConnection(relayUrl: String): String = withContext(Dispatchers.IO) {
    try {
        val baseUrl = relayUrl
            .substringBeforeLast("/")
            .replace("wss://", "https://")
            .replace("ws://", "http://")
        val url = URL("$baseUrl/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..299) "ok" else "fail"
    } catch (e: Exception) {
        Log.d("RelayServers", "Test fehlgeschlagen: ${e.message}")
        "fail"
    }
}
