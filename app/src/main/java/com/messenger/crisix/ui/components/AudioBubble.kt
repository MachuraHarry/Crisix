package com.messenger.crisix.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import com.messenger.crisix.util.AudioPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

private const val BAR_COUNT = 40

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
    var wasPlayed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }

    val accentColor = if (isFromMe) Color.White else Color.White
    val bgColor = if (isFromMe) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f)
    val trackColor = if (isFromMe) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.2f)

    val barHeights = remember(audioUri) {
        val rng = Random(audioUri.hashCode())
        List(BAR_COUNT) { 0.15f + rng.nextFloat() * 0.85f }
    }
    val density = LocalDensity.current

    // Beobachte, ob eine andere AudioBubble den Player übernommen hat
    val activeUri by AudioPlayer.activeUriFlow.collectAsState()
    LaunchedEffect(activeUri) {
        if (activeUri != null && activeUri.toString() != audioUri && isPlaying) {
            isPlaying = false
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis()
            var lastPos = 0L
            while (isPlaying) {
                delay(100)
                val pos = AudioPlayer.currentPosition
                if (pos > 0) lastPos = pos
                elapsedMs = lastPos.coerceAtMost(durationMs)
                progress = if (durationMs > 0) elapsedMs.toFloat() / durationMs else 0f
                if (progress >= 1f || (!AudioPlayer.isPlaying && !AudioPlayer.isPaused)) {
                    isPlaying = false
                    progress = 1f
                    elapsedMs = durationMs
                    break
                }
            }
        }
    }

    fun seekTo(fraction: Float) {
        val targetMs = (fraction * durationMs).toLong().coerceIn(0, durationMs)
        if (isPlaying) {
            AudioPlayer.seekTo(targetMs)
            elapsedMs = targetMs
            progress = fraction
        }
    }

    val speeds = listOf(1f, 1.5f, 2f)

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
                .clickable {
                    if (isPlaying) {
                        AudioPlayer.pause()
                        isPlaying = false
                    } else if (AudioPlayer.isPaused && AudioPlayer.currentUriString == audioUri) {
                        AudioPlayer.resume()
                        isPlaying = true
                    } else {
                        wasPlayed = true
                        AudioPlayer.playFrom(context, Uri.parse(audioUri), 0L) {
                            isPlaying = false
                            progress = 1f
                            elapsedMs = durationMs
                        }
                        AudioPlayer.setSpeed(speed)
                        isPlaying = true
                    }
                },
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
                    .height(36.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val barWidthPx = size.width.toFloat()
                            val fraction = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            seekTo(fraction)
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    val barWidth = (size.width - (BAR_COUNT - 1) * 2f) / BAR_COUNT
                    val midY = size.height / 2f

                    barHeights.forEachIndexed { i, height ->
                        val barHeight = height * size.height * 0.7f
                        val x = i * (barWidth + 2f)
                        val top = midY - barHeight / 2f
                        val isPlayed = (i.toFloat() / BAR_COUNT) <= progress

                        drawRoundRect(
                            color = if (isPlayed) accentColor.copy(alpha = 0.9f) else trackColor,
                            topLeft = Offset(x, top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                barWidth / 2f, barWidth / 2f
                            )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDuration(elapsedMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = accentColor.copy(alpha = 0.8f),
                    )
                    if (!wasPlayed && !isPlaying) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.6f))
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "-${formatDuration(durationMs - elapsedMs)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = accentColor.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .clickable {
                                val idx = speeds.indexOf(speed)
                                val next = speeds[(idx + 1) % speeds.size]
                                speed = next
                                AudioPlayer.setSpeed(next)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${speed.toInt()}×",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (speed > 1f) accentColor else accentColor.copy(alpha = 0.5f),
                        )
                    }
                }
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
