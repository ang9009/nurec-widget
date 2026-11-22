package dev.nurec.widget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nurec.widget.data.RecRepository
import dev.nurec.widget.data.UiState
import dev.nurec.widget.widget.RecWidget
import java.util.concurrent.TimeUnit

/** Fetches the API, caches it, redraws the widget. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = RecRepository(applicationContext)
        // Manual refreshes already claimed the cooldown slot before enqueueing.
        val claimed = inputData.getBoolean(KEY_MANUAL, false) || repo.tryStartAttempt()
        if (!claimed) {
            RecWidget().updateAll(applicationContext)
            return Result.success()
        }
        // Failures are recorded in the cache, not retried: the next periodic run tries again,
        // and fast retries would defeat the cooldown.
        repo.fetch()
        // Redraw either way: on failure the widget shows cached data plus the offline marker.
        RecWidget().updateAll(applicationContext)
        scheduleCooldownEnd(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "rec-refresh-periodic"
        private const val ONE_TIME = "rec-refresh-now"
        private const val KEY_MANUAL = "manual"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Enqueue a fetch. Call only after [RecRepository.tryStartAttempt] returned true. */
        fun enqueueManual(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(networkConstraint)
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
            scheduleCooldownEnd(context)
        }

        /** Redraw the widget when the cooldown ends so the button unlocks. */
        fun scheduleCooldownEnd(context: Context) {
            val request = OneTimeWorkRequestBuilder<RedrawWorker>()
                .setInitialDelay(UiState.COOLDOWN.seconds + 1, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(RedrawWorker.NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(ONE_TIME)
                cancelUniqueWork(RedrawWorker.NAME)
            }
        }
    }
}

/** No network: just repaints the widget (used when the refresh cooldown ends). */
class RedrawWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RecWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "rec-redraw"
    }
}
