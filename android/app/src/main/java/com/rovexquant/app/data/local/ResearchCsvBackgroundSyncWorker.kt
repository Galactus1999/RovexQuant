package com.rovexquant.app.data.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rovexquant.app.data.repository.QuantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Background counterpart of the Research Lab refresh action. It reuses the same
 * SAF importer, encrypted session store, repository and Data Hub sync manager.
 */
class ResearchCsvBackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("research_csv_auto_sync", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return Result.success()
        val session = withContext(Dispatchers.IO) { QuantSessionStore.load(applicationContext) } ?: run {
            ResearchCsvBackgroundSyncScheduler.cancel(applicationContext)
            return Result.success()
        }
        val repository = QuantRepository(applicationContext)

        return try {
            if (!repository.validateSession(session)) {
                QuantSessionStore.clearSession(applicationContext)
                ResearchCsvBackgroundSyncScheduler.cancel(applicationContext)
                return Result.success()
            }

            ResearchCsvFolderImporter.savedFolderUri(applicationContext)?.let { folderUri ->
                try {
                    withContext(Dispatchers.IO) {
                        ResearchCsvFolderImporter.scan(applicationContext, folderUri)
                    }
                } catch (_: SecurityException) {
                    // SAF permission was revoked; stop repeatedly retrying an inaccessible folder.
                    ResearchCsvFolderImporter.clearFolder(applicationContext)
                }
            }

            val hasDatasets = withContext(Dispatchers.IO) {
                ResearchCsvDatasetStore.list(applicationContext).isNotEmpty()
            }
            if (!hasDatasets) {
                record("No imported instrument datasets are available.", null)
                return Result.success()
            }

            val report = ResearchCsvAutoSyncManager.sync(
                applicationContext, repository, session, force = false
            )
            record(report.summary, report.errors.firstOrNull())
            when {
                report.busy || report.failedDatasets > 0 -> retryOrFail()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            record("Network unavailable; background refresh will retry.", failure.message)
            retryOrFail()
        } catch (failure: Exception) {
            record("Background refresh failed.", failure.message)
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()

    private fun record(status: String, detail: String?) {
        applicationContext.getSharedPreferences("research_csv_auto_sync", Context.MODE_PRIVATE)
            .edit()
            .putLong("background_last_attempt_at", System.currentTimeMillis())
            .putString("background_last_status", status.take(240))
            .putString("background_last_error", detail?.take(240))
            .apply()
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 4
    }
}
