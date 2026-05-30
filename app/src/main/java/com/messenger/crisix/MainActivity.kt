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
import com.messenger.crisix.util.NotificationHelper

class MainActivity : ComponentActivity() {

    /**
     * Aktuelle Sprache als State, damit die App bei Sprachwechsel neu rendert.
     */
    var currentLanguage by mutableStateOf(LocaleHelper.AppLanguage.GERMAN)
        private set

    /**
     * Chat-ID aus Notification-DeepLink (wenn über Notification geöffnet).
     */
    var notificationOpenChatId by mutableStateOf<String?>(null)
        private set

    /**
     * Chat-Name aus Notification-DeepLink.
     */
    var notificationOpenChatName by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notification-Channel erstellen (wichtig: vor jeder Notification!)
        NotificationHelper.createNotificationChannel(this)

        // DeepLink aus Intent extrahieren (wenn über Notification geöffnet)
        handleIntent(intent)

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
                        notificationOpenChatId = notificationOpenChatId,
                        notificationOpenChatName = notificationOpenChatName,
                        onNotificationHandled = {
                            notificationOpenChatId = null
                            notificationOpenChatName = null
                        },
                        onLanguageChanged = { newLanguage ->
                            currentLanguage = newLanguage
                            LocaleHelper.setLanguage(this@MainActivity, newLanguage)
                            recreate()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.hasExtra("openChatId") == true) {
            notificationOpenChatId = intent.getStringExtra("openChatId")
            notificationOpenChatName = intent.getStringExtra("openChatName")
        }
    }
}
