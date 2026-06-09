package com.messenger.crisix.ui.screens

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.ai.AiModelManager
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.RelayServer
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

    val notificationSoundUri: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATION_SOUND_URI] ?: ""
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    val readReceiptsEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.READ_RECEIPTS_ENABLED] ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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

    val voiceMessageQuality: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.VOICE_MESSAGE_QUALITY] ?: "standard"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "standard")

    val fontScale: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.FONT_SCALE] ?: "normal"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "normal")

    val reducedMotion: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.REDUCED_MOTION] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val highContrast: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HIGH_CONTRAST] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    val developerMode: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.DEVELOPER_MODE] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val logLevel: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.LOG_LEVEL] ?: "debug"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "debug")

    val aiGpuLayers: StateFlow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_GPU_LAYERS] ?: 99
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 99)

    val aiVulkanDisabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_VULKAN_DISABLED] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aiContextSize: StateFlow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_CONTEXT_SIZE] ?: 4096
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4096)

    val aiBatchSize: StateFlow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_BATCH_SIZE] ?: 512
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 512)

    val aiThreads: StateFlow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_THREADS] ?: 4
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    val aiKvCacheType: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AI_KV_CACHE_TYPE] ?: "F16"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "F16")

    val relayServers: StateFlow<List<RelayServer>> = dataStore.data.map { prefs ->
        val json = prefs[SettingsKeys.RELAY_SERVERS] ?: defaultRelayServersJson
        parseRelayServers(json)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), parseRelayServers(defaultRelayServersJson))

    fun setThemeMode(mode: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.THEME_MODE] = mode } }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = enabled } }
    }

    fun setNotificationSound(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATION_SOUND] = enabled } }
    }

    fun setNotificationSoundUri(uri: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.NOTIFICATION_SOUND_URI] = uri } }
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

    fun setReadReceiptsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.READ_RECEIPTS_ENABLED] = enabled } }
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

    fun setVoiceMessageQuality(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.VOICE_MESSAGE_QUALITY] = value } }
    }

    fun setFontScale(value: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.FONT_SCALE] = value } }
    }

    fun setReducedMotion(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.REDUCED_MOTION] = enabled } }
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.HIGH_CONTRAST] = enabled } }
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

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.DEVELOPER_MODE] = enabled } }
    }

    fun setLogLevel(level: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.LOG_LEVEL] = level } }
    }

    fun setAiGpuLayers(layers: Int) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_GPU_LAYERS] = layers } }
    }

    fun setAiVulkanDisabled(disabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_VULKAN_DISABLED] = disabled } }
    }

    fun setAiContextSize(size: Int) {
        val clamped = size.coerceAtMost(AiModelManager.MAX_CONTEXT_SIZE)
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_CONTEXT_SIZE] = clamped } }
    }

    fun setAiBatchSize(size: Int) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_BATCH_SIZE] = size } }
    }

    fun setAiThreads(threads: Int) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_THREADS] = threads } }
    }

    fun setAiKvCacheType(type: String) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AI_KV_CACHE_TYPE] = type } }
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

    fun addRelayServer(name: String, url: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = parseRelayServers(prefs[SettingsKeys.RELAY_SERVERS] ?: defaultRelayServersJson)
                val newList = current + RelayServer(id = UUID.randomUUID().toString(), name = name, url = url)
                prefs[SettingsKeys.RELAY_SERVERS] = serializeRelayServers(newList)
            }
        }
    }

    fun updateRelayServer(id: String, name: String, url: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = parseRelayServers(prefs[SettingsKeys.RELAY_SERVERS] ?: defaultRelayServersJson)
                val newList = current.map { if (it.id == id) it.copy(name = name, url = url) else it }
                prefs[SettingsKeys.RELAY_SERVERS] = serializeRelayServers(newList)
            }
        }
    }

    fun removeRelayServer(id: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = parseRelayServers(prefs[SettingsKeys.RELAY_SERVERS] ?: defaultRelayServersJson)
                val newList = current.filter { it.id != id }
                prefs[SettingsKeys.RELAY_SERVERS] = serializeRelayServers(newList)
            }
        }
    }

    fun reorderRelayServers(servers: List<RelayServer>) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.RELAY_SERVERS] = serializeRelayServers(servers)
            }
        }
    }

    private fun serializeRelayServers(servers: List<RelayServer>): String {
        val arr = JSONArray()
        servers.forEach { server ->
            arr.put(JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("url", server.url)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val defaultRelayServersJson =
            """[{"id":"default","name":"Render","url":"wss://crisix-dns.onrender.com/ws"}]"""

        fun parseRelayServers(json: String): List<RelayServer> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RelayServer(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        url = obj.optString("url", "")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
