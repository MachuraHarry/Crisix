package com.messenger.crisix

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.messenger.crisix.worker.MessageCleanupWorker
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberTree
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CrisixApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotEmpty()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.1
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
