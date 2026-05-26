package com.messenger.crisix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.messenger.crisix.ui.navigation.CrisixApp
import com.messenger.crisix.ui.theme.CrisixTheme

class MainActivity : ComponentActivity() {

    /**
     * Aktuelle Sprache als State, damit die App bei Sprachwechsel neu rendert.
     */
    var currentLanguage by mutableStateOf(LocaleHelper.AppLanguage.GERMAN)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gespeicherte Sprache laden und anwenden
        currentLanguage = LocaleHelper.getLanguage(this)
        LocaleHelper.updateLocale(this, currentLanguage)

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
