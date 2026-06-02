package com.messenger.crisix.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.messenger.crisix.R
import com.messenger.crisix.util.DateGroup
import com.messenger.crisix.util.getDateGroup
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun dateLabel(timestampMillis: Long): String {
    return when (getDateGroup(timestampMillis)) {
        DateGroup.TODAY -> stringResource(R.string.date_today)
        DateGroup.YESTERDAY -> stringResource(R.string.date_yesterday)
        DateGroup.THIS_WEEK -> stringResource(R.string.date_this_week)
        DateGroup.OLDER -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
            java.text.SimpleDateFormat("d. MMMM", java.util.Locale.getDefault()).format(cal.time)
        }
    }
}

internal fun startRecording(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val audioDir = File(context.filesDir, "audio")
    audioDir.mkdirs()
    scope.launch {
        try {
            com.messenger.crisix.util.AudioRecorder.startRecording(context, audioDir)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar(context.getString(R.string.chat_detail_mic_unavailable))
        }
    }
}

internal fun formatTimerShort(ms: Long): String = when (ms) {
    0L -> "\u23F0"
    30_000L -> "30s"
    300_000L -> "5m"
    3_600_000L -> "1h"
    86_400_000L -> "24h"
    604_800_000L -> "7d"
    else -> "\u23F0"
}

@androidx.compose.runtime.Composable
internal fun formatTimerLabel(ms: Long): String = when (ms) {
    30_000L -> stringResource(R.string.timer_30s)
    300_000L -> stringResource(R.string.timer_5m)
    3_600_000L -> stringResource(R.string.timer_1h)
    86_400_000L -> stringResource(R.string.timer_24h)
    604_800_000L -> stringResource(R.string.timer_7d)
    else -> stringResource(R.string.timer_off)
}
