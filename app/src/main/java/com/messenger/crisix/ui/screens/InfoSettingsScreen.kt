package com.messenger.crisix.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.BuildConfig
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.ClickablePreference
import com.messenger.crisix.ui.components.SettingsScaffold
import com.messenger.crisix.ui.components.SettingsSectionTitle
import com.messenger.crisix.ui.components.SwitchPreference
import com.messenger.crisix.update.UpdateManager
import kotlinx.coroutines.launch

@Composable
fun InfoSettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val autoUpdateEnabled by vm.autoUpdateEnabled.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateManager.state.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.info_title),
        onBackClick = onBackClick
    ) {
        SettingsSectionTitle(title = stringResource(R.string.info_title))

        ClickablePreference(
            icon = R.drawable.ic_info,
            title = stringResource(R.string.info_version),
            subtitle = stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            onClick = {
                scope.launch { UpdateManager.checkForUpdate(context, force = true) }
            },
            trailing = {
                if (updateState is UpdateManager.UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else if (updateState is UpdateManager.UpdateState.UpToDate) {
                    Text(
                        text = "\u2713",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.update_check_button),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        when (val state = updateState) {
            is UpdateManager.UpdateState.UpdateAvailable -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.update_available_title, state.versionName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.update_size, formatFileSize(state.sizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.changelog.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { UpdateManager.downloadUpdate(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(R.string.update_download_button))
                        }
                    }
                }
            }

            is UpdateManager.UpdateState.Downloading -> {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.update_downloading, (state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            is UpdateManager.UpdateState.ReadyToInstall -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.update_ready_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.update_ready_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { UpdateManager.installUpdate(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(R.string.update_install_button))
                        }
                    }
                }
            }

            is UpdateManager.UpdateState.Error -> {
                Text(
                    text = stringResource(R.string.update_error, state.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            else -> {}
        }

        Spacer(modifier = Modifier.height(8.dp))

        SwitchPreference(
            icon = R.drawable.ic_cloud,
            title = stringResource(R.string.info_auto_update),
            subtitle = stringResource(R.string.info_auto_update_desc),
            checked = autoUpdateEnabled,
            onCheckedChange = { vm.setAutoUpdateEnabled(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ClickablePreference(
            icon = R.drawable.ic_globe,
            title = stringResource(R.string.info_github),
            subtitle = stringResource(R.string.info_github_desc),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anomalyco/Crisix"))
                context.startActivity(intent)
            }
        )

        ClickablePreference(
            icon = R.drawable.ic_info,
            title = stringResource(R.string.info_licenses),
            subtitle = stringResource(R.string.info_licenses_desc),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/anomalyco/Crisix/blob/main/LICENSE"))
                context.startActivity(intent)
            }
        )

        ClickablePreference(
            icon = R.drawable.ic_person,
            title = stringResource(R.string.info_developer),
            subtitle = stringResource(R.string.info_developer_value),
            onClick = {}
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
