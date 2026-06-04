package com.messenger.crisix.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.SettingsScaffold
import com.messenger.crisix.ui.components.SettingsSectionTitle
import com.messenger.crisix.ui.components.SwitchPreference

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

    SettingsScaffold(
        title = stringResource(R.string.settings_notifications),
        onBackClick = onBackClick,
        modifier = modifier
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
