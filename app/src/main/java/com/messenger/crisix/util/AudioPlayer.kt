package com.messenger.crisix.util

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentUri: Uri? = null

    private val _activeUri = MutableStateFlow<Uri?>(null)
    val activeUriFlow: StateFlow<Uri?> = _activeUri

    fun play(context: Context, uri: Uri, onCompletion: () -> Unit) {
        stop()
        currentUri = uri
        _activeUri.value = uri
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            start()
            setOnCompletionListener { onCompletion() }
        }
    }

    fun playFrom(context: Context, uri: Uri, startMs: Long, onCompletion: () -> Unit) {
        stop()
        currentUri = uri
        _activeUri.value = uri
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            seekTo(startMs.toInt())
            start()
            setOnCompletionListener { onCompletion() }
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        currentUri = null
        _activeUri.value = null
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
    }

    fun setSpeed(speed: Float) {
        mediaPlayer?.let { mp ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = PlaybackParams().setSpeed(speed)
                mp.playbackParams = params
            }
        }
    }

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true

    val currentPosition: Long get() = mediaPlayer?.currentPosition?.toLong() ?: 0L

    val currentUriString: String?
        get() = currentUri?.toString()

    val isPaused: Boolean
        get() {
            val mp = mediaPlayer ?: return false
            return !mp.isPlaying && mp.currentPosition > 0
        }

    fun pause() {
        mediaPlayer?.pause()
    }

    fun resume() {
        mediaPlayer?.start()
    }
}
