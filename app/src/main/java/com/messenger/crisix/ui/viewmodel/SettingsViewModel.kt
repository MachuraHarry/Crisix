package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.messenger.crisix.LocaleHelper
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.screens.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val transportSettings: Map<TransportType, Boolean> = emptyMap(),
    val userProfile: UserProfile = UserProfile(),
    val currentLanguage: LocaleHelper.AppLanguage = LocaleHelper.AppLanguage.GERMAN,
    val relayHost: String = "192.168.178.32",
    val relayPort: Int = 54232,
    val updateState: String = "idle",
)

class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun toggleTransport(type: TransportType, enabled: Boolean) {
        _state.update { current ->
            current.copy(transportSettings = current.transportSettings + (type to enabled))
        }
    }

    fun updateProfile(profile: UserProfile) {
        _state.update { it.copy(userProfile = profile) }
    }

    fun changeLanguage(language: LocaleHelper.AppLanguage) {
        _state.update { it.copy(currentLanguage = language) }
    }

    fun updateRelayConfig(host: String, port: Int) {
        _state.update { it.copy(relayHost = host, relayPort = port) }
    }

    fun setUpdateState(state: String) {
        _state.update { it.copy(updateState = state) }
    }

    fun loadTransportSettings(settings: Map<TransportType, Boolean>) {
        _state.update { it.copy(transportSettings = settings) }
    }

    fun loadUserProfile(profile: UserProfile) {
        _state.update { it.copy(userProfile = profile) }
    }

    fun loadRelayConfig(host: String, port: Int) {
        _state.update { it.copy(relayHost = host, relayPort = port) }
    }
}
