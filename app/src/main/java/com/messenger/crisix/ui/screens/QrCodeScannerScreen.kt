package com.messenger.crisix.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vollbild-QR-Code-Scanner mit CameraX und ZXing.
 *
 * ZXing ist die bewährteste QR-Code-Bibliothek für Android und
 * verarbeitet YUV_420_888-Daten nativ ohne Umwege.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScannerScreen(
    onQrCodeScanned: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    val scanningActive = remember { AtomicBoolean(true) }
    val frameCounter = remember { AtomicInteger(0) }

    // Kamera-Berechtigung prüfen
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "QR-Code scannen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.messenger.crisix.R.drawable.ic_arrow_back),
                            contentDescription = "Zurück"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (!hasCameraPermission) {
                Text(
                    text = "Kamera-Berechtigung erforderlich.\nBitte in den Einstellungen aktivieren.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // PreviewView als remember-Referenz
                    val previewView = remember {
                        PreviewView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    }

                    // AndroidView zeigt den PreviewView an
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // CameraX + ZXing in DisposableEffect starten
                    DisposableEffect(lifecycleOwner) {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                        // ZXing MultiFormatReader – nur QR-Codes, für bessere Performance
                        val reader = MultiFormatReader().apply {
                            setHints(mapOf(
                                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(
                                    com.google.zxing.BarcodeFormat.QR_CODE
                                ),
                                com.google.zxing.DecodeHintType.TRY_HARDER to true,
                                com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8"
                            ))
                        }

                        val listener = Runnable {
                            try {
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder()
                                    .build()
                                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                                // Höhere Auflösung für bessere QR-Erkennung
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                val analyzerExecutor = Executors.newSingleThreadExecutor()
                                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    if (scanningActive.get()) {
                                        val frameNum = frameCounter.incrementAndGet()
                                        scanWithZxing(imageProxy, reader, frameNum) { result ->
                                            if (result != null) {
                                                scanningActive.set(false)
                                                onQrCodeScanned(result)
                                            }
                                        }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                val cameraSelector = CameraSelector.Builder()
                                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                    .build()

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("QrCodeScanner", "CameraX Fehler: ${e.message}", e)
                            }
                        }

                        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

                        onDispose {
                            cameraProviderFuture.get().unbindAll()
                        }
                    }

                    // Scan-Overlay
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(250.dp)
                                .border(
                                    width = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp)
                                )
                        )
                    }

                    Text(
                        text = "QR-Code in den Rahmen halten",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Analysiert ein Kamerabild mit ZXing.
 *
 * ZXing benötigt für die QR-Code-Erkennung nur die Luminanz (Y-Kanal).
 * Daher extrahieren wir direkt die Y-Ebene aus YUV_420_888 und
 * übergeben sie an PlanarYUVLuminanceSource – das ist schneller
 * und vermeidet Fehler bei der NV21-Konvertierung.
 */
private fun scanWithZxing(
    imageProxy: ImageProxy,
    reader: MultiFormatReader,
    frameNum: Int,
    onResult: (String?) -> Unit
) {
    try {
        val width = imageProxy.width
        val height = imageProxy.height

        // Nur jeden 3. Frame analysieren
        if (frameNum % 3 != 0) {
            imageProxy.close()
            return
        }

        Log.d("QrCodeScanner", "Frame #$frameNum: ${width}x${height}, format=${imageProxy.format}")

        // YUV_420_888: Y-Ebene (Luminanz) extrahieren
        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // Y-Daten zeilenweise kopieren (rowStride kann > width sein)
        val yData = ByteArray(width * height)
        var destOffset = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                yData[destOffset++] = yBuffer.get(rowStart + col * yPixelStride)
            }
        }

        // ZXing: Luminanzquelle aus den Y-Daten erstellen
        // PlanarYUVLuminanceSource kann auch nur mit Y-Daten umgehen,
        // wenn wir width und height korrekt angeben.
        val source = PlanarYUVLuminanceSource(
            yData,           // nur Y-Luminanz-Daten
            width,           // Bildbreite
            height,          // Bildhöhe
            0,               // linkes Crop
            0,               // oberes Crop
            width,           // Crop-Breite
            height,          // Crop-Höhe
            false            // nicht horizontal gespiegelt
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            val text = result.text
            if (text != null && text.isNotEmpty()) {
                Log.d("QrCodeScanner", ">>> QR-Code erkannt! <<< $text")
                onResult(text)
                imageProxy.close()
                return
            }
        } catch (e: NotFoundException) {
            // Kein QR-Code in diesem Frame – normal
        } finally {
            reader.reset()
        }

        imageProxy.close()
    } catch (e: Exception) {
        Log.e("QrCodeScanner", "Scan-Fehler: ${e.message}", e)
        imageProxy.close()
    }
}
