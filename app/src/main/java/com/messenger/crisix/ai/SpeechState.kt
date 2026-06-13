package com.messenger.crisix.ai

sealed class SpeechState {
    data object Idle : SpeechState()
    data object Downloading : SpeechState()
    data object Loading : SpeechState()
    data object Ready : SpeechState()
    data object Listening : SpeechState()
    data object Transcribing : SpeechState()
    data object Speaking : SpeechState()
    data class Error(val message: String) : SpeechState()
}
