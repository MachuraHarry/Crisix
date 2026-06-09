package com.messenger.crisix.ai

sealed class AiRuntimeState {
    data object Idle : AiRuntimeState()
    data object Loading : AiRuntimeState()
    data class Ready(val contextId: Int) : AiRuntimeState()
    data class Generating(val contextId: Int) : AiRuntimeState()
    data object Cancelling : AiRuntimeState()
    data class Error(val message: String, val recoverable: Boolean) : AiRuntimeState()
}

sealed class DownloadProgress {
    data object Idle : DownloadProgress()
    data class Downloading(
        val progress: Float,
        val partIndex: Int,
        val partCount: Int,
        val speedBytesPerSec: Long,
    ) : DownloadProgress()
    data object Extracting : DownloadProgress()
    data object Initializing : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
    data object Complete : DownloadProgress()
}
