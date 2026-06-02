package com.messenger.crisix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.messenger.crisix.BuildConfig
import com.messenger.crisix.service.CrisixForegroundService
import com.messenger.crisix.ui.navigation.CrisixApp
import com.messenger.crisix.ui.navigation.DeepLinkData
import com.messenger.crisix.ui.theme.CrisixTheme
import com.messenger.crisix.util.NotificationHelper
import timber.log.Timber

class MainActivity : ComponentActivity() {

    var currentLanguage by mutableStateOf(LocaleHelper.AppLanguage.GERMAN)
        private set

    var notificationOpenChatId by mutableStateOf<String?>(null)
        private set

    var notificationOpenChatName by mutableStateOf<String?>(null)
        private set

    var deepLinkData by mutableStateOf<DeepLinkData?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        NotificationHelper.createChannels(this)
        CrisixForegroundService.createServiceChannel(this)

        handleIntent(intent)

        currentLanguage = LocaleHelper.getLanguage(this)
        LocaleHelper.updateLocale(this, currentLanguage)

        CrisixForegroundService.start(this)

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
                        deepLinkData = deepLinkData,
                        onDeepLinkHandled = {
                            deepLinkData = null
                        },
                        onLanguageChanged = { newLanguage ->
                            currentLanguage = newLanguage
                            LocaleHelper.setLanguage(this@MainActivity, newLanguage)
                            recreate()
                        },
                        onChatOpened = { chatId ->
                            NotificationHelper.cancelChatNotifications(this, chatId)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.hasExtra("openChatId") == true) {
            notificationOpenChatId = intent.getStringExtra("openChatId")
            notificationOpenChatName = intent.getStringExtra("openChatName")
        }

        intent?.data?.let { uri ->
            deepLinkData = parseCrisixUri(uri)
        }
    }

    private fun parseCrisixUri(uri: Uri): DeepLinkData? {
        if (uri.scheme != "crisix") return null

        return try {
            when (uri.host) {
                "contact" -> {
                    val peerId = uri.getQueryParameter("key")
                    val name = uri.getQueryParameter("name")
                    val ip = uri.getQueryParameter("ip")
                    val port = uri.getQueryParameter("port")?.toIntOrNull()
                    if (peerId != null) {
                        DeepLinkData(
                            type = DeepLinkData.Type.CONTACT,
                            peerId = peerId,
                            peerName = name ?: peerId.take(8),
                            ipAddress = ip,
                            port = port,
                        )
                    } else null
                }
                "handshake" -> {
                    val bundleB64 = uri.getQueryParameter("bundle")
                    val name = uri.getQueryParameter("name") ?: "Unknown"
                    val key = uri.getQueryParameter("key")
                    if (bundleB64 != null) {
                        DeepLinkData(
                            type = DeepLinkData.Type.HANDSHAKE,
                            peerId = key,
                            peerName = name,
                            handshakeBundleB64 = bundleB64,
                        )
                    } else null
                }
                else -> {
                    Log.w("MainActivity", "Unknown crisix deep link: ${uri.host}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse crisix URI: ${e.message}")
            null
        }
    }
}
