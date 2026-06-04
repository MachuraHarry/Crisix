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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.ui.components.ClickablePreference
import com.messenger.crisix.ui.components.SettingsSectionTitle

private val backgroundColors = listOf(
    Color(0xFF0D1B2A),
    Color(0xFF1B2838),
    Color(0xFF243447),
    Color(0xFF000000),
    Color(0xFF1A1A2E),
    Color(0xFF16213E),
    Color(0xFF0F3460),
    Color(0xFF2C2C2C),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val fontScale by vm.fontScale.collectAsState()
    val fontFamily by vm.fontFamily.collectAsState()
    val chatBubbleStyle by vm.chatBubbleStyle.collectAsState()
    val chatBackgroundColor by vm.chatBackgroundColor.collectAsState()

    var showFontScaleDialog by remember { mutableStateOf(false) }
    var showFontFamilyDialog by remember { mutableStateOf(false) }
    var showBubbleStyleDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }

    val fontScaleOptions = listOf(
        "normal" to stringResource(R.string.settings_appearance_font_normal),
        "large" to stringResource(R.string.settings_appearance_font_large),
        "xlarge" to stringResource(R.string.settings_appearance_font_xlarge),
    )

    val fontFamilyOptions = listOf(
        "system" to stringResource(R.string.settings_appearance_font_system),
        "monospace" to stringResource(R.string.settings_appearance_font_mono),
    )

    val bubbleStyleOptions = listOf(
        "standard" to stringResource(R.string.settings_appearance_bubble_standard),
        "compact" to stringResource(R.string.settings_appearance_bubble_compact),
    )

    val fontScaleLabel = fontScaleOptions.find { it.first == fontScale }?.second
        ?: stringResource(R.string.settings_appearance_font_normal)
    val fontFamilyLabel = fontFamilyOptions.find { it.first == fontFamily }?.second
        ?: stringResource(R.string.settings_appearance_font_system)
    val bubbleStyleLabel = bubbleStyleOptions.find { it.first == chatBubbleStyle }?.second
        ?: stringResource(R.string.settings_appearance_bubble_standard)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_appearance),
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
            SettingsSectionTitle(title = stringResource(R.string.settings_appearance_text))

            ClickablePreference(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.settings_appearance_font_size),
                subtitle = fontScaleLabel,
                onClick = { showFontScaleDialog = true }
            )

            ClickablePreference(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.settings_appearance_font_family),
                subtitle = fontFamilyLabel,
                onClick = { showFontFamilyDialog = true }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsSectionTitle(title = stringResource(R.string.settings_appearance_chat))

            ClickablePreference(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.settings_appearance_bubble_style),
                subtitle = bubbleStyleLabel,
                onClick = { showBubbleStyleDialog = true }
            )

            ClickablePreference(
                icon = R.drawable.ic_chat,
                title = stringResource(R.string.settings_appearance_background),
                subtitle = stringResource(R.string.settings_appearance_background_desc),
                onClick = { showBackgroundDialog = true }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showFontScaleDialog) {
        AlertDialog(
            onDismissRequest = { showFontScaleDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_appearance_font_size),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    fontScaleOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setFontScale(value)
                                    showFontScaleDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (fontScale == value) {
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
                TextButton(onClick = { showFontScaleDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    if (showFontFamilyDialog) {
        AlertDialog(
            onDismissRequest = { showFontFamilyDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_appearance_font_family),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    fontFamilyOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setFontFamily(value)
                                    showFontFamilyDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (fontFamily == value) {
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
                TextButton(onClick = { showFontFamilyDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    if (showBubbleStyleDialog) {
        AlertDialog(
            onDismissRequest = { showBubbleStyleDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_appearance_bubble_style),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    bubbleStyleOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setChatBubbleStyle(value)
                                    showBubbleStyleDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (chatBubbleStyle == value) {
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
                TextButton(onClick = { showBubbleStyleDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_appearance_background),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.settings_appearance_background_choose),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        backgroundColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        vm.setChatBackgroundColor(
                                            android.graphics.Color.argb(
                                                (color.alpha * 255).toInt(),
                                                (color.red * 255).toInt(),
                                                (color.green * 255).toInt(),
                                                (color.blue * 255).toInt()
                                            )
                                        )
                                        showBackgroundDialog = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (chatBackgroundColor == android.graphics.Color.argb(
                                        (color.alpha * 255).toInt(),
                                        (color.red * 255).toInt(),
                                        (color.green * 255).toInt(),
                                        (color.blue * 255).toInt()
                                    )
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_check),
                                        contentDescription = stringResource(R.string.settings_selected),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackgroundDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }
}
