package com.messenger.crisix.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.messenger.crisix.data.MessageRepository
import timber.log.Timber

class MessageCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val repository = MessageRepository(applicationContext)
            val deleted = repository.cleanAllExpiredMessages()
            if (deleted > 0) {
                Timber.i("MessageCleanupWorker: $deleted expired messages deleted")
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "MessageCleanupWorker failed")
            Result.retry()
        }
    }
}
