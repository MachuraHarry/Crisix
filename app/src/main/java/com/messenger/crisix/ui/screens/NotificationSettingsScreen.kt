package com.messenger.crisix.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
fun NotificationSettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val notificationsEnabled by vm.notificationsEnabled.collectAsState()
    val notificationSound by vm.notificationSound.collectAsState()
    val notificationVibration by vm.notificationVibration.collectAsState()
    val notificationPreview by vm.notificationPreview.collectAsState()
    val screenOnForNotification by vm.screenOnForNotification.collectAsState()
    val notificationSoundUri by vm.notificationSoundUri.collectAsState()

    val context = LocalContext.current
    val soundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            vm.setNotificationSoundUri(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_notifications),
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
            SettingsSectionTitle(title = stringResource(R.string.settings_notifications_general))

            SwitchPreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_enable),
                subtitle = stringResource(R.string.settings_notifications_enable_desc),
                checked = notificationsEnabled,
                onCheckedChange = vm::setNotificationsEnabled
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_notifications_behavior))

            SwitchPreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_sound),
                subtitle = stringResource(R.string.settings_notifications_sound_desc),
                checked = notificationSound,
                onCheckedChange = vm::setNotificationSound,
                enabled = notificationsEnabled
            )

            ClickablePreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_sound_uri),
                subtitle = if (notificationSoundUri.isNotBlank())
                    stringResource(R.string.settings_notifications_sound_uri_set)
                else
                    stringResource(R.string.settings_notifications_sound_uri_desc),
                onClick = {
                    soundPickerLauncher.launch(arrayOf("audio/*"))
                },
                enabled = notificationSound && notificationsEnabled,
                trailing = {
                    if (notificationSoundUri.isNotBlank()) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            SwitchPreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_vibration),
                subtitle = stringResource(R.string.settings_notifications_vibration_desc),
                checked = notificationVibration,
                onCheckedChange = vm::setNotificationVibration,
                enabled = notificationsEnabled
            )

            SwitchPreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_preview),
                subtitle = stringResource(R.string.settings_notifications_preview_desc),
                checked = notificationPreview,
                onCheckedChange = vm::setNotificationPreview,
                enabled = notificationsEnabled
            )

            SwitchPreference(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.settings_notifications_screen_on),
                subtitle = stringResource(R.string.settings_notifications_screen_on_desc),
                checked = screenOnForNotification,
                onCheckedChange = vm::setScreenOnForNotification,
                enabled = notificationsEnabled
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
