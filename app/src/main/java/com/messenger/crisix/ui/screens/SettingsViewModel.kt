package com.messenger.crisix.ui.screens

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.settingsDataStore

    val themeMode: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.THEME_MODE] ?: "system"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val notificationsEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationSound: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATION_SOUND] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationVibration: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATION_VIBRATION] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val notificationPreview: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATION_PREVIEW] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val enterToSend: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.ENTER_TO_SEND] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val screenLockEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.SCREEN_LOCK_ENABLED] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hideInRecent: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HIDE_IN_RECENT] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val mediaAutoDownload: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.MEDIA_AUTO_DOWNLOAD] ?: "wifi"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "wifi")

    val screenOnForNotification: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.SCREEN_ON_FOR_NOTIFICATION] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoAddContacts: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AUTO_ADD_CONTACTS] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val dataSaverMode: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.DATA_SAVER_MODE] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val fontScale: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.FONT_SCALE] ?: "normal"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "normal")

    val fontFamily: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.FONT_FAMILY] ?: "system"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val chatBubbleStyle: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.CHAT_BUBBLE_STYLE] ?: "standard"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "standard")

    val chatBackgroundColor: StateFlow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.CHAT_BACKGROUND_COLOR] ?: 0xFF0D1B2A.toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFF0D1B2A.toInt())

    val transportOrder: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TRANSPORT_ORDER] ?: ""
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val autoUpdateEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AUTO_UPDATE_ENABLED] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setThemeMode(mode: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.THEME_MODE] = mode } }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = enabled } }
    }

    fun setNotificationSound(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATION_SOUND] = enabled } }
    }

    fun setNotificationVibration(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATION_VIBRATION] = enabled } }
    }

    fun setNotificationPreview(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATION_PREVIEW] = enabled } }
    }

    fun setScreenOnForNotification(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.SCREEN_ON_FOR_NOTIFICATION] = enabled } }
    }

    fun setEnterToSend(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.ENTER_TO_SEND] = enabled } }
    }

    fun setScreenLockEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.SCREEN_LOCK_ENABLED] = enabled } }
    }

    fun setHideInRecent(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.HIDE_IN_RECENT] = enabled } }
    }

    fun setMediaAutoDownload(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.MEDIA_AUTO_DOWNLOAD] = value } }
    }

    fun setAutoAddContacts(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AUTO_ADD_CONTACTS] = enabled } }
    }

    fun setDataSaverMode(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.DATA_SAVER_MODE] = enabled } }
    }

    fun setFontScale(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.FONT_SCALE] = value } }
    }

    fun setFontFamily(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.FONT_FAMILY] = value } }
    }

    fun setChatBubbleStyle(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.CHAT_BUBBLE_STYLE] = value } }
    }

    fun setChatBackgroundColor(value: Int) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.CHAT_BACKGROUND_COLOR] = value } }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AUTO_UPDATE_ENABLED] = enabled } }
    }

    fun setTransportOrder(order: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.TRANSPORT_ORDER] = order } }
    }

    fun clearAllSettings() {
        viewModelScope.launch {
            dataStore.edit { it.clear() }
            val app = getApplication<Application>()
            val setupPrefs = app.getSharedPreferences("crisix_setup", android.content.Context.MODE_PRIVATE)
            setupPrefs.edit().clear().apply()
        }
    }
}
