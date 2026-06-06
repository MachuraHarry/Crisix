package com.messenger.crisix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.BuildConfig
import com.messenger.crisix.LocaleHelper
import com.messenger.crisix.R
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.ClickablePreference
import com.messenger.crisix.ui.components.SettingsSectionTitle
import com.messenger.crisix.ui.components.SwitchPreference
import kotlinx.coroutines.launch

data class UserProfile(
    val name: String = "",
    val status: String = "Hallo! Ich bin bei Crisix.",
    val avatarColor: Color = Color(0xFF00475D)
)

private val avatarColors = listOf(
    Color(0xFF00475D),
    Color(0xFF1B3A5C),
    Color(0xFF0D47A1),
    Color(0xFFB71C1C),
    Color(0xFFE65100),
    Color(0xFF01579B),
    Color(0xFF37474F),
    Color(0xFF263238),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    transportSettings: Map<TransportType, Boolean>,
    onTransportToggle: (TransportType, Boolean) -> Unit,
    userProfile: UserProfile,
    onProfileUpdate: (UserProfile) -> Unit,
    onLanguageChanged: (LocaleHelper.AppLanguage) -> Unit = {},
    onBackClick: () -> Unit,
    onOpenLogViewer: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenChatSettings: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {},
    onOpenInfo: () -> Unit = {},
    onOpenTransportPriority: () -> Unit = {},
    onOpenRelayServers: () -> Unit = {},
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel? = null
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    var showEditDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var sectionExpanded by remember { mutableStateOf(mapOf(
        "transport" to true,
        "tools" to true,
        "info" to true
    )) }

    fun toggleSection(section: String) {
        sectionExpanded = sectionExpanded + (section to !(sectionExpanded[section] ?: true))
    }

    val transportOrder by vm.transportOrder.collectAsState()

    val developerMode by vm.developerMode.collectAsState()
    val logLevel by vm.logLevel.collectAsState()

    var showLogLevelDialog by remember { mutableStateOf(false) }

    val sortedTransports = remember(transportSettings, transportOrder) {
        if (transportOrder.isNotBlank()) {
            val names = transportOrder.split(",")
            val customOrder = names.mapNotNull { name ->
                TransportType.entries.find { it.name == name }
            }
            val remaining = TransportType.entries.filter { it.name !in names }
            val ordered = customOrder + remaining
            ordered.mapNotNull { type -> transportSettings[type]?.let { type to it } }
        } else {
            TransportType.entries.mapNotNull { type -> transportSettings[type]?.let { type to it } }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(R.string.settings_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* no-op */ })
            )

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.settings_title))) {
                ProfileSection(
                    userProfile = userProfile,
                    onClick = { showEditDialog = true }
                )
            }

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.language))) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                SettingsSectionTitle(title = stringResource(R.string.language))

                ClickablePreference(
                    icon = R.drawable.ic_person,
                    title = stringResource(R.string.language),
                    subtitle = stringResource(R.string.language_german) + " / " + stringResource(R.string.language_english),
                    onClick = { showLanguageDialog = true },
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.settings_preferences))) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                SettingsSectionTitle(title = stringResource(R.string.settings_preferences))

                ClickablePreference(
                    icon = R.drawable.ic_notifications,
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.settings_notifications_desc),
                    onClick = onOpenNotifications,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                ClickablePreference(
                    icon = R.drawable.ic_info,
                    title = stringResource(R.string.settings_privacy),
                    subtitle = stringResource(R.string.settings_privacy_desc),
                    onClick = onOpenPrivacy,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                ClickablePreference(
                    icon = R.drawable.ic_chat,
                    title = stringResource(R.string.settings_chat),
                    subtitle = stringResource(R.string.settings_chat_desc),
                    onClick = onOpenChatSettings,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                ClickablePreference(
                    icon = R.drawable.ic_info,
                    title = stringResource(R.string.settings_appearance),
                    subtitle = stringResource(R.string.settings_appearance_desc),
                    onClick = onOpenAppearance,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                ClickablePreference(
                    icon = R.drawable.ic_info,
                    title = stringResource(R.string.settings_accessibility),
                    subtitle = stringResource(R.string.settings_accessibility_desc),
                    onClick = onOpenAccessibility,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.transport_settings_title))) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleSection("transport") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.transport_settings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (sectionExpanded["transport"] == true) "▾" else "▸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (sectionExpanded["transport"] == true) {
                    Text(
                        text = stringResource(R.string.transport_settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )

                    sortedTransports.forEach { (type, enabled) ->
                        val (iconRes, label, description) = transportInfo(type)
                        val isComingSoon = type == TransportType.SMS || type == TransportType.LORA
                        SwitchPreference(
                            icon = iconRes,
                            title = label,
                            subtitle = description + if (isComingSoon) " (Coming Soon)" else "",
                            checked = enabled,
                            onCheckedChange = { onTransportToggle(type, it) },
                            enabled = !isComingSoon
                        )
                    }

                    ClickablePreference(
                        icon = R.drawable.ic_network,
                        title = stringResource(R.string.transport_priority_title),
                        subtitle = stringResource(R.string.transport_priority_hint),
                        onClick = onOpenTransportPriority,
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_info),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    ClickablePreference(
                        icon = R.drawable.ic_network,
                        title = stringResource(R.string.settings_relay_servers),
                        subtitle = stringResource(R.string.settings_relay_servers_desc),
                        onClick = onOpenRelayServers,
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_info),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.settings_app_log))) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleSection("tools") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_tools),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (sectionExpanded["tools"] == true) "▾" else "▸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (sectionExpanded["tools"] == true) {
                    ClickablePreference(
                        icon = R.drawable.ic_info,
                        title = stringResource(R.string.settings_show_log),
                        subtitle = stringResource(R.string.settings_log_entries, InAppLogger.logs.size),
                        onClick = onOpenLogViewer,
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_info),
                                contentDescription = stringResource(R.string.settings_open_log),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    SwitchPreference(
                        icon = R.drawable.ic_info,
                        title = stringResource(R.string.settings_developer_mode),
                        subtitle = stringResource(R.string.settings_developer_mode_desc),
                        checked = developerMode,
                        onCheckedChange = vm::setDeveloperMode
                    )

                    if (developerMode) {
                        ClickablePreference(
                            icon = R.drawable.ic_info,
                            title = stringResource(R.string.settings_log_level),
                            subtitle = logLevelDisplayName(logLevel, context),
                            onClick = { showLogLevelDialog = true }
                        )
                    }
                }
            }

            if (searchQuery.isEmpty() || matchesSearch(searchQuery, stringResource(R.string.info_title))) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                SettingsSectionTitle(title = stringResource(R.string.info_title))

                ClickablePreference(
                    icon = R.drawable.ic_info,
                    title = stringResource(R.string.info_title),
                    subtitle = stringResource(R.string.info_settings_desc),
                    onClick = onOpenInfo,
                    trailing = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_reset))

            ClickablePreference(
                icon = R.drawable.ic_delete,
                title = stringResource(R.string.settings_reset_all),
                subtitle = stringResource(R.string.settings_reset_all_desc),
                onClick = { showResetDialog = true }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showEditDialog) {
        EditProfileDialog(
            currentProfile = userProfile,
            onSave = { updatedProfile ->
                onProfileUpdate(updatedProfile)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_reset_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.settings_reset_confirm_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllSettings()
                    showResetDialog = false
                }) {
                    Text(
                        stringResource(R.string.settings_reset_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                onLanguageChanged(language)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showLogLevelDialog) {
        LogLevelDialog(
            currentLevel = logLevel,
            onSelect = { level ->
                vm.setLogLevel(level)
                showLogLevelDialog = false
            },
            onDismiss = { showLogLevelDialog = false }
        )
    }
}

private val logLevels = listOf(
    "debug" to "Debug",
    "info" to "Info",
    "warning" to "Warning",
    "error" to "Error"
)

private fun logLevelDisplayName(level: String, context: android.content.Context): String {
    return logLevels.find { it.first == level }?.second ?: "Debug"
}

@Composable
private fun LogLevelDialog(
    currentLevel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_log_level),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                logLevels.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (currentLevel == value) {
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}

private fun matchesSearch(query: String, text: String): Boolean {
    return text.contains(query, ignoreCase = true)
}

@Composable
private fun ProfileSection(
    userProfile: UserProfile,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(userProfile.avatarColor.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = userProfile.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = userProfile.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = userProfile.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_section_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_person),
            contentDescription = stringResource(R.string.profile_edit),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(
    currentProfile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentProfile.name) }
    var status by remember { mutableStateOf(currentProfile.status) }
    var selectedColor by remember { mutableStateOf(currentProfile.avatarColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.profile_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(selectedColor.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(8) },
                    label = { Text(stringResource(R.string.profile_name_label)) },
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
                    value = status,
                    onValueChange = { status = it },
                    label = { Text(stringResource(R.string.profile_status_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (name.isNotBlank()) {
                                onSave(UserProfile(name, status, selectedColor))
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.profile_avatar_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    avatarColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == color) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.settings_selected),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(UserProfile(name, status, selectedColor))
                    }
                },
                enabled = name.isNotBlank()
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

@Composable
private fun LanguageSelectionDialog(
    onLanguageSelected: (LocaleHelper.AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.language),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                LocaleHelper.AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}

@Composable
private fun transportInfo(type: TransportType): Triple<Int, String, String> {
    return when (type) {
        TransportType.RELAY -> Triple(
            R.drawable.ic_network,
            stringResource(R.string.transport_relay_label),
            stringResource(R.string.transport_relay_desc)
        )
        TransportType.INTERNET -> Triple(
            R.drawable.ic_network,
            stringResource(R.string.transport_internet_label),
            stringResource(R.string.transport_internet_desc)
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
            R.drawable.ic_network,
            stringResource(R.string.transport_dns_label),
            stringResource(R.string.transport_dns_desc)
        )
        TransportType.LORA -> Triple(
            R.drawable.ic_network,
            stringResource(R.string.transport_lora_label),
            stringResource(R.string.transport_lora_desc)
        )
    }
}


