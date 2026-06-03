package com.messenger.crisix

import android.app.Application
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberTree
import timber.log.Timber

class CrisixApplication : Application() {

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
    }
}
