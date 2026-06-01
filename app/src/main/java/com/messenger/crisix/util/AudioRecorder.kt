package com.messenger.crisix.util

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

object AudioRecorder {
    private const val TAG = "AudioRecorder"
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartTime: Long = 0L

    /**
     * Startet die Audio-Aufnahme als AAC-Datei.
     * @return Das erstellte File-Objekt
     */
    suspend fun startRecording(context: Context, outputDir: File): File {
        val file = File(outputDir, "voice_${System.currentTimeMillis()}.aac")
        cleanup()
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
        recordingStartTime = System.currentTimeMillis()
        return file
    }

    /**
     * Stoppt die Aufnahme und gibt die Audio-Daten + Dauer zurück.
     * @return Pair(audioBytes, durationMs) — leeres ByteArray bei Fehler
     */
    suspend fun stopRecording(): Pair<ByteArray, Long> {
        val durationMs = if (recordingStartTime > 0) {
            System.currentTimeMillis() - recordingStartTime
        } else 0L

        mediaRecorder?.apply {
            try {
                stop()
            } catch (e: Exception) {
                // MediaRecorder.stop() kann IllegalStateException werfen
                // wenn vorher schon cancelled wurde
                outputFile?.let { if (it.exists()) it.delete() }
                outputFile = null
                recordingStartTime = 0L
            }
            release()
        }
        mediaRecorder = null

        val data = if (outputFile?.exists() == true) {
            outputFile?.readBytes() ?: byteArrayOf()
        } else {
            byteArrayOf()
        }
        outputFile = null
        recordingStartTime = 0L
        return Pair(data, durationMs)
    }

    fun cancelRecording() {
        cleanup()
        outputFile?.delete()
        outputFile = null
        recordingStartTime = 0L
    }

    private fun cleanup() {
        mediaRecorder?.apply {
            try { stop() } catch (e: Exception) { Log.w(TAG, "MediaRecorder stop failed: ${e.message}", e) }
            release()
        }
        mediaRecorder = null
    }
}
