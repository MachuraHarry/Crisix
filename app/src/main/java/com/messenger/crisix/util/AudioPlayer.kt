package com.messenger.crisix.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun play(context: Context, uri: Uri, onCompletion: () -> Unit) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
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
    }

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true
}
