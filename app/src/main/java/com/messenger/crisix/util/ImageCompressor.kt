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
            ?: return@withContext byteArrayOf()
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        val originalBitmap = bitmap ?: return@withContext byteArrayOf()

        var currentBitmap = originalBitmap
        var currentQuality = quality

        while (true) {
            val (width, height) = if (currentBitmap.width > maxDimension || currentBitmap.height > maxDimension) {
                val ratio = minOf(maxDimension.toFloat() / currentBitmap.width, maxDimension.toFloat() / currentBitmap.height)
                Pair((currentBitmap.width * ratio).toInt(), (currentBitmap.height * ratio).toInt())
            } else {
                Pair(currentBitmap.width, currentBitmap.height)
            }

            val scaled = Bitmap.createScaledBitmap(currentBitmap, width, height, true)
            if (scaled !== currentBitmap) currentBitmap.recycle()

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, currentQuality, output)

            val bytes = output.toByteArray()

            if (maxSizeBytes <= 0 || bytes.size <= maxSizeBytes || currentQuality <= 5) {
                scaled.recycle()
                return@withContext bytes
            }

            currentQuality = (currentQuality * 0.7).toInt().coerceAtLeast(5)
            currentBitmap = scaled
        }
        @Suppress("UNREACHABLE_CODE")
        byteArrayOf()
    }
}
