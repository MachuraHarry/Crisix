package com.messenger.crisix.ui.components

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.util.AudioPlayer
import kotlinx.coroutines.delay

@Composable
fun AudioBubble(
    audioUri: String,
    durationMs: Long,
    isFromMe: Boolean,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }

    val accentColor = if (isFromMe) {
        Color.White
    } else {
        Color(0xFF34C759)
    }

    val bgColor = if (isFromMe) {
        Color.White.copy(alpha = 0.15f)
    } else {
        Color(0xFF34C759).copy(alpha = 0.12f)
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis()
            while (isPlaying) {
                delay(100)
                val now = System.currentTimeMillis()
                val currentMs = now - startTime
                elapsedMs = currentMs.coerceAtMost(durationMs)
                progress = if (durationMs > 0) elapsedMs.toFloat() / durationMs else 0f
                if (progress >= 1f) {
                    isPlaying = false
                    progress = 0f
                    elapsedMs = 0L
                    break
                }
            }
        }
    }

    fun togglePlay() {
        if (isPlaying) {
            AudioPlayer.stop()
            isPlaying = false
        } else {
            AudioPlayer.play(context, Uri.parse(audioUri)) {
                isPlaying = false
                progress = 0f
                elapsedMs = 0L
            }
            isPlaying = true
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(bgColor)
                .clickable { togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = if (isPlaying) painterResource(R.drawable.ic_pause)
                else painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                ) {
                    val barHeight = 6.dp.toPx()
                    val barY = size.height / 2 - barHeight / 2
                    val cornerRadius = barHeight / 2

                    drawRoundRect(
                        color = accentColor.copy(alpha = 0.2f),
                        topLeft = Offset(0f, barY),
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )

                    if (progress > 0f) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(0f, barY),
                            size = Size(size.width * progress, barHeight),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isPlaying) formatDuration(elapsedMs) else formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = accentColor.copy(alpha = 0.8f),
                )
                Text(
                    text = "-${formatDuration(durationMs - elapsedMs)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = accentColor.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.width(4.dp))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
