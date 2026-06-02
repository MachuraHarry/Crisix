package com.messenger.crisix.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.messenger.crisix.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

@Composable
fun ImagePreviewDialog(
    imageUri: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = stringResource(R.string.image_preview_description),
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onDismiss() },
                contentScale = ContentScale.Fit,
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = stringResource(R.string.image_preview_close),
                    tint = Color.White,
                )
            }

            FilledTonalButton(
                onClick = {
                    scope.launch {
                        saveImageToGallery(context, imageUri)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(stringResource(R.string.image_save_to_gallery))
            }
        }
    }
}

private suspend fun saveImageToGallery(context: Context, imageUri: String) {
    try {
        val bytes = withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(android.net.Uri.parse(imageUri))
            inputStream?.use { it.readBytes() }
        } ?: return

        val filename = "Crisix_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Crisix")
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return

        withContext(Dispatchers.IO) {
            val outputStream: OutputStream? = resolver.openOutputStream(uri)
            outputStream?.use { it.write(bytes) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.image_saved_to_gallery), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.image_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
