package com.messenger.crisix.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "crisix_settings")

object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
    val NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
    val NOTIFICATION_PREVIEW = booleanPreferencesKey("notification_preview")
    val SCREEN_LOCK_ENABLED = booleanPreferencesKey("screen_lock_enabled")
    val HIDE_IN_RECENT = booleanPreferencesKey("hide_in_recent")
    val READ_RECEIPTS_ENABLED = booleanPreferencesKey("read_receipts_enabled")
    val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
    val MEDIA_AUTO_DOWNLOAD = stringPreferencesKey("media_auto_download")
    val DATA_SAVER_MODE = booleanPreferencesKey("data_saver_mode")
    val LANGUAGE_OVERRIDE = stringPreferencesKey("language_override")
    val FONT_SCALE = stringPreferencesKey("font_scale")
    val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
    val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
    val LOG_LEVEL = stringPreferencesKey("log_level")

    val SCREEN_ON_FOR_NOTIFICATION = booleanPreferencesKey("screen_on_for_notification")
    val VOICE_MESSAGE_QUALITY = stringPreferencesKey("voice_message_quality")
    val AUTO_ADD_CONTACTS = booleanPreferencesKey("auto_add_contacts")
    val NOTIFICATION_SOUND_URI = stringPreferencesKey("notification_sound_uri")

    val FONT_FAMILY = stringPreferencesKey("font_family")
    val CHAT_BUBBLE_STYLE = stringPreferencesKey("chat_bubble_style")
    val CHAT_BACKGROUND_COLOR = intPreferencesKey("chat_background_color")

    val AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
    val TRANSPORT_ORDER = stringPreferencesKey("transport_order")
    val RELAY_SERVERS = stringPreferencesKey("relay_servers")
    val PHONE_NUMBER = stringPreferencesKey("phone_number")

    // AI / Crisix AI
    val AI_MODEL_DOWNLOADED = booleanPreferencesKey("ai_model_downloaded")
    val AI_MODEL_URL = stringPreferencesKey("ai_model_url")
    val AI_MODEL_PARTS = intPreferencesKey("ai_model_parts")
    val AI_GPU_LAYERS = intPreferencesKey("ai_gpu_layers")
    val AI_CONTEXT_SIZE = intPreferencesKey("ai_context_size")
    val AI_BATCH_SIZE = intPreferencesKey("ai_batch_size")
    val AI_THREADS = intPreferencesKey("ai_threads")
    val AI_VULKAN_DISABLED = booleanPreferencesKey("ai_vulkan_disabled")
    val AI_KV_CACHE_TYPE = stringPreferencesKey("ai_kv_cache_type")
    val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")

    // Auto hardware config
    val AI_AUTO_CONFIG_APPLIED = booleanPreferencesKey("ai_auto_config_applied")
    val AI_AUTO_CONFIG_VERSION = intPreferencesKey("ai_auto_config_version")
    val AI_AUTO_RAM_MB = intPreferencesKey("ai_auto_ram_mb")
    val AI_AUTO_CPU = intPreferencesKey("ai_auto_cpu")
}

data class RelayServer(
    val id: String,
    val name: String,
    val url: String
)
