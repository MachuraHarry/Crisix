package com.messenger.crisix.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ImageCompressor {
    /**
     * Komprimiert ein Bild von einem URI.
     *
     * @param maxDimension Maximale Breite/Höhe in Pixeln (Seitenverhältnis beibehalten)
     * @param quality JPEG-Qualität (0-100, kleiner = stärker komprimiert)
     * @param maxSizeBytes Wenn > 0: Qualität wird automatisch reduziert bis das Bild
     *                     in dieses Budget passt (oder quality=5 erreicht)
     */
    suspend fun compress(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024,
        quality: Int = 80,
        maxSizeBytes: Int = 0,
    ): ByteArray = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        var currentQuality = quality

        while (true) {
            val (width, height) = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                Pair((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
            } else {
                Pair(bitmap.width, bitmap.height)
            }

            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) bitmap.recycle()

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, currentQuality, output)
            scaled.recycle()

            val bytes = output.toByteArray()

            // Prüfen ob Größe akzeptabel ist
            if (maxSizeBytes <= 0 || bytes.size <= maxSizeBytes || currentQuality <= 5) {
                return@withContext bytes
            }

            // Qualität reduzieren und erneut versuchen
            currentQuality = (currentQuality * 0.7).toInt().coerceAtLeast(5)
        }
        @Suppress("UNREACHABLE_CODE")
        byteArrayOf()
    }
}
