package com.messenger.crisix.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
fun PrivacySettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val screenLockEnabled by vm.screenLockEnabled.collectAsState()
    val hideInRecent by vm.hideInRecent.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.settings_privacy),
        onBackClick = onBackClick,
        modifier = modifier
    ) {
        SettingsSectionTitle(title = stringResource(R.string.settings_privacy_security))

        SwitchPreference(
            icon = R.drawable.ic_notifications,
            title = stringResource(R.string.settings_privacy_screen_lock),
            subtitle = stringResource(R.string.settings_privacy_screen_lock_desc),
            checked = screenLockEnabled,
            onCheckedChange = vm::setScreenLockEnabled
        )

        SwitchPreference(
            icon = R.drawable.ic_notifications,
            title = stringResource(R.string.settings_privacy_hide_recent),
            subtitle = stringResource(R.string.settings_privacy_hide_recent_desc),
            checked = hideInRecent,
            onCheckedChange = vm::setHideInRecent
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
