package com.rovexquant.app.data.local

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Owns the single periodic refresh schedule for imported daily CSV datasets. */
object ResearchCsvBackgroundSyncScheduler {
    const val UNIQUE_WORK_NAME = "rovexquant-imported-dataset-refresh"
    const val INTERVAL_HOURS = 4L
    private const val PREFS = "research_csv_auto_sync"
    private const val ENABLED_KEY = "enabled"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) ensureScheduled(context) else cancel(context)
    }

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val enabled = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, true)
        if (!enabled || !QuantSessionStore.hasStoredSession(appContext)) {
            cancel(appContext)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ResearchCsvBackgroundSyncWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
