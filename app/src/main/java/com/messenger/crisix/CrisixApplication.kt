package com.messenger.crisix

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.messenger.crisix.ai.AiModelManager
import com.messenger.crisix.worker.MessageCleanupWorker
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberTree
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CrisixApplication : Application(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "CrisixApp"
    }

    override fun onCreate() {
        super.onCreate()

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotEmpty()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.tracesSampleRate = 1.0
                options.isEnableUserInteractionTracing = true
                options.isAttachScreenshot = false
            }
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        if (dsn.isNotEmpty()) {
            Timber.plant(
                SentryTimberTree(
                    hub = Sentry.getCurrentHub(),
                    minEventLevel = SentryLevel.ERROR,
                    minBreadcrumbLevel = SentryLevel.INFO,
                )
            )
        }

        scheduleMessageCleanup()
    }

    // --- Memory pressure handling ---

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "Memory pressure level=$level, keeping AI model")
                // Moderate pressure: keep model but suggest GC
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure level=$level, unloading AI model")
                unloadAiModel()
            }
            else -> {
                // TRIM_MEMORY_UI_HIDDEN etc. – no action needed
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory – unloading AI model")
        unloadAiModel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handled by Activity recreation
    }

    private fun unloadAiModel() {
        try {
            AiModelManager.getInstance(this).unloadModel()
        } catch (_: Exception) {
            // Instance might not be initialized yet – ignore
        }
    }

    // --- Worker scheduling ---

    private fun scheduleMessageCleanup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val cleanupRequest = PeriodicWorkRequestBuilder<MessageCleanupWorker>(
            15, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "message_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest,
        )
    }
}
