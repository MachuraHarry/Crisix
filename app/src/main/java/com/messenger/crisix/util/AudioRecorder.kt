package com.messenger.crisix.util

import android.content.Context
import android.media.MediaRecorder
import java.io.File

object AudioRecorder {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    suspend fun startRecording(context: Context, outputDir: File): File {
        val file = File(outputDir, "voice_${System.currentTimeMillis()}.aac")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        outputFile = file
        return file
    }

    suspend fun stopRecording(): ByteArray {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        return outputFile?.readBytes() ?: byteArrayOf()
    }

    fun cancelRecording() {
        mediaRecorder?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
    }
}
