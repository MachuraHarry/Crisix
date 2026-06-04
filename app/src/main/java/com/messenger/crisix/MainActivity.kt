package com.messenger.crisix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.service.CrisixForegroundService
import com.messenger.crisix.ui.navigation.CrisixApp
import com.messenger.crisix.ui.navigation.DeepLinkData
import com.messenger.crisix.ui.screens.SettingsViewModel
import com.messenger.crisix.ui.theme.CrisixTheme
import com.messenger.crisix.util.NotificationHelper

class MainActivity : FragmentActivity() {

    var currentLanguage by mutableStateOf(LocaleHelper.AppLanguage.GERMAN)
        private set

    var notificationOpenChatId by mutableStateOf<String?>(null)
        private set

    var notificationOpenChatName by mutableStateOf<String?>(null)
        private set

    var deepLinkData by mutableStateOf<DeepLinkData?>(null)
        private set

    private var isLocked by mutableStateOf(true)
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannels(this)
        CrisixForegroundService.createServiceChannel(this)

        handleIntent(intent)

        currentLanguage = LocaleHelper.getLanguage(this)
        LocaleHelper.updateLocale(this, currentLanguage)

        CrisixForegroundService.start(this)

        enableEdgeToEdge()

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isLocked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w("MainActivity", "Biometric auth error $errorCode: $errString")
                }

                override fun onAuthenticationFailed() {
                    Log.w("MainActivity", "Biometric auth failed (fingerprint not recognized)")
                }
            }
        )

        setContent {
            val settingsVM = viewModel<SettingsViewModel>()
            val fontScale by settingsVM.fontScale.collectAsState()
            val fontFamilyName by settingsVM.fontFamily.collectAsState()
            val hideInRecent by settingsVM.hideInRecent.collectAsState()
            val screenLockEnabled by settingsVM.screenLockEnabled.collectAsState()

            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            isLocked = true
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            if (isLocked && screenLockEnabled) {
                                biometricPrompt.authenticate(
                                    BiometricPrompt.PromptInfo.Builder()
                                        .setTitle(getString(R.string.settings_privacy_screen_lock))
                                        .setSubtitle(getString(R.string.settings_privacy_screen_lock_biometric_hint))
                                        .setAllowedAuthenticators(
                                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                        )
                                        .build()
                                )
                            }
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(hideInRecent) {
                if (hideInRecent) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            if (isLocked && screenLockEnabled) {
                LockContent(
                    onUnlock = {
                        biometricPrompt.authenticate(
                            BiometricPrompt.PromptInfo.Builder()
                                .setTitle(getString(R.string.settings_privacy_screen_lock))
                                .setSubtitle(getString(R.string.settings_privacy_screen_lock_biometric_hint))
                                .setAllowedAuthenticators(
                                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                )
                                .build()
                        )
                    }
                )
            } else {
                CrisixTheme(
                    fontScale = fontScale,
                    fontFamilyName = fontFamilyName,
                ) {
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
            Log.e("MainActivity", "Failed to parse crisix URI", e)
            null
        }
    }
}

@Composable
private fun LockContent(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_notifications),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.settings_privacy_screen_lock_locked),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_privacy_screen_lock_biometric_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.settings_privacy_screen_lock_unlock)
                )
            }
        }
    }
}
