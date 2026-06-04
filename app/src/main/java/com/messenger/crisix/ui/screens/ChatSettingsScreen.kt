package com.messenger.crisix.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.ClickablePreference
import com.messenger.crisix.ui.components.SettingsSectionTitle
import com.messenger.crisix.ui.components.SwitchPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val enterToSend by vm.enterToSend.collectAsState()
    val mediaAutoDownload by vm.mediaAutoDownload.collectAsState()
    val autoAddContacts by vm.autoAddContacts.collectAsState()
    val dataSaverMode by vm.dataSaverMode.collectAsState()

    var showMediaDownloadDialog by remember { mutableStateOf(false) }

    val mediaOptions = listOf(
        "wifi" to stringResource(R.string.settings_chat_media_wifi),
        "mobile" to stringResource(R.string.settings_chat_media_mobile),
        "never" to stringResource(R.string.settings_chat_media_never),
    )

    val mediaLabel = mediaOptions.find { it.first == mediaAutoDownload }?.second
        ?: stringResource(R.string.settings_chat_media_wifi)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_chat),
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
            SettingsSectionTitle(title = stringResource(R.string.settings_chat_sending))

            SwitchPreference(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.settings_chat_enter_to_send),
                subtitle = stringResource(R.string.settings_chat_enter_to_send_desc),
                checked = enterToSend,
                onCheckedChange = vm::setEnterToSend
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_chat_media))

            ClickablePreference(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.settings_chat_media_download),
                subtitle = mediaLabel,
                onClick = { showMediaDownloadDialog = true }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_chat_contacts))

            SwitchPreference(
                icon = R.drawable.ic_person,
                title = stringResource(R.string.settings_chat_auto_add),
                subtitle = stringResource(R.string.settings_chat_auto_add_desc),
                checked = autoAddContacts,
                onCheckedChange = vm::setAutoAddContacts
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_chat_data))

            SwitchPreference(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.settings_chat_data_saver),
                subtitle = stringResource(R.string.settings_chat_data_saver_desc),
                checked = dataSaverMode,
                onCheckedChange = vm::setDataSaverMode
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showMediaDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showMediaDownloadDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_chat_media_download),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    mediaOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setMediaAutoDownload(value)
                                    showMediaDownloadDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (mediaAutoDownload == value) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMediaDownloadDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }
}
