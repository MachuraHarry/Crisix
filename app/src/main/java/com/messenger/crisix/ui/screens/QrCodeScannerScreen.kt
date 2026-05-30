package com.messenger.crisix.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hochmoderner QR-Code Scanner, implementiert wie in Signal.
 * Nutzt Google ML Kit für extrem schnelles und robustes Decoding und
 * CameraX UseCaseGroups für eine perfekte Abstimmung zwischen Vorschau und Analyse.
 *
 * Zeigt nach erfolgreichem Scan ein grünes Feedback (Haken + Text) für 1,5 Sekunden.
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
    // Feedback-State: zeigt grünen Haken + Text nach erfolgreichem Scan
    var showFeedback by remember { mutableStateOf(false) }
    var scannedContent by remember { mutableStateOf("") }

    // Berechtigung prüfen
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("QR-Code scannen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = com.messenger.crisix.R.drawable.ic_arrow_back),
                            contentDescription = "Zurück"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!hasCameraPermission) {
                Text(
                    "Kamera-Berechtigung erforderlich",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                // Der PreviewView muss im remember bleiben, um Rekonfigurationen zu überstehen
                val previewView = remember {
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }

                // CameraX & ML Kit Lifecycle-Anbindung
                DisposableEffect(lifecycleOwner) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    val analysisExecutor = Executors.newSingleThreadExecutor()
                    // Lokaler Guard – jede neue DisposableEffect bekommt ein frisches true
                    val scanningGuard = AtomicBoolean(true)

                    // ML Kit Scanner Instanz (wie in Signal konfiguriert)
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val barcodeScanner = BarcodeScanning.getClient(options)

                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            // 1. Preview Use Case
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // 2. Image Analysis Use Case
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (scanningGuard.get()) {
                                    analyzeImage(imageProxy, barcodeScanner) { result ->
                                        // Nur das erste erfolgreiche Ergebnis verarbeiten
                                        if (scanningGuard.compareAndSet(true, false)) {
                                            InAppLogger.i("QrScanner", "QR-Code erkannt: $result")
                                            scannedContent = result
                                            showFeedback = true
                                            ContextCompat.getMainExecutor(context).execute {
                                                onQrCodeScanned(result)
                                            }
                                        }
                                    }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            // WICHTIG: ViewPort sorgt für korrektes Seitenverhältnis und Fokus
                            val viewPort = previewView.viewPort

                            cameraProvider.unbindAll()

                            if (viewPort != null) {
                                val useCaseGroup = UseCaseGroup.Builder()
                                    .addUseCase(preview)
                                    .addUseCase(imageAnalysis)
                                    .setViewPort(viewPort)
                                    .build()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, cameraSelector, useCaseGroup
                                )
                            } else {
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                                )
                            }

                        } catch (e: Exception) {
                            InAppLogger.e("QrScanner", "CameraX Bind-Fehler", e)
                        }
                    }, ContextCompat.getMainExecutor(context))

                    onDispose {
                        analysisExecutor.shutdown()
                        barcodeScanner.close()
                    }
                }

                // Kamera-Vorschau anzeigen
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Signal-Style Overlay (Schatten mit ausgeschnittenem Fenster)
                SignalViewfinderOverlay()

                // === Scan-Erfolg-Feedback ===
                AnimatedVisibility(
                    visible = showFeedback,
                    enter = fadeIn() + scaleIn(initialScale = 0.5f),
                    exit = fadeOut() + scaleOut(targetScale = 1.2f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Grüner Kreis mit Haken
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .background(Color(0xFF4CAF50), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Text unter dem Kreis
                        Text(
                            text = "QR-Code erkannt!",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                        )
                    }
                }

                // Feedback nach 1,5s automatisch ausblenden
                if (showFeedback) {
                    LaunchedEffect(Unit) {
                        delay(1500)
                        showFeedback = false
                    }
                }
            }
        }
    }
}

@Composable
fun SignalViewfinderOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val rectSize = width * 0.7f
        val left = (width - rectSize) / 2
        val top = (height - rectSize) / 2
        val viewfinderRect = Rect(left, top, left + rectSize, top + rectSize)

        // 1. Alles abdunkeln
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            size = Size(width, height)
        )

        // 2. Das Scanner-Fenster "freischneiden"
        drawRoundRect(
            color = Color.Transparent,
            topLeft = viewfinderRect.topLeft,
            size = viewfinderRect.size,
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        // 3. Den weißen Rahmen zeichnen
        drawRoundRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = viewfinderRect.topLeft,
            size = viewfinderRect.size,
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun analyzeImage(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onResult: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (rawValue != null) {
                        onResult(rawValue)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnFailureListener {
                // Analysefehler sind bei schnellen Frame-Raten normal
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
