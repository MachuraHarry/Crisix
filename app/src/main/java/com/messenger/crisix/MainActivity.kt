package com.messenger.crisix

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.messenger.crisix.ui.navigation.CrisixApp
import com.messenger.crisix.ui.theme.CrisixTheme

class MainActivity : ComponentActivity() {

    /**
     * Aktuelle Sprache als State, damit die App bei Sprachwechsel neu rendert.
     */
    var currentLanguage by mutableStateOf(LocaleHelper.AppLanguage.GERMAN)
        private set

    /**
     * Launcher für die Kamera-Berechtigungsanfrage.
     */
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Kamera-Berechtigung erteilt – QR-Code-Scanner kann verwendet werden
            android.util.Log.d("MainActivity", "Kamera-Berechtigung erteilt")
        } else {
            android.util.Log.w("MainActivity", "Kamera-Berechtigung verweigert")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gespeicherte Sprache laden und anwenden
        currentLanguage = LocaleHelper.getLanguage(this)
        LocaleHelper.updateLocale(this, currentLanguage)

        // Kamera-Berechtigung anfordern (für QR-Code-Scanner)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        enableEdgeToEdge()
        setContent {
            CrisixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CrisixApp(
                        onLanguageChanged = { newLanguage ->
                            currentLanguage = newLanguage
                            LocaleHelper.setLanguage(this@MainActivity, newLanguage)
                            // Activity neu starten, damit alle Ressourcen neu geladen werden
                            recreate()
                        }
                    )
                }
            }
        }
    }
}
