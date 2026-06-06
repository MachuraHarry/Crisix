package com.messenger.crisix.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.SettingsSectionTitle
import com.messenger.crisix.ui.components.SwitchPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val reducedMotion by vm.reducedMotion.collectAsState()
    val highContrast by vm.highContrast.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_accessibility),
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
            SettingsSectionTitle(title = stringResource(R.string.settings_accessibility_display))

            SwitchPreference(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.settings_accessibility_reduced_motion),
                subtitle = stringResource(R.string.settings_accessibility_reduced_motion_desc),
                checked = reducedMotion,
                onCheckedChange = vm::setReducedMotion
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SwitchPreference(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.settings_accessibility_high_contrast),
                subtitle = stringResource(R.string.settings_accessibility_high_contrast_desc),
                checked = highContrast,
                onCheckedChange = vm::setHighContrast
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
