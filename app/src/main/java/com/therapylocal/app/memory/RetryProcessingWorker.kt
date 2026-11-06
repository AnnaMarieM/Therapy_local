package com.therapylocal.app.memory

import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.util.concurrent.TimeUnit

/**
 * Starts the processing queue again after a run was deferred because no engine host could serve (a restart storm, bind failures; see
 * `JobRunner.drain` and `EngineUnavailable`). The job, its chunks and the pending audio were kept, so this only has to call
 * [MemoryService.start] once the delay has passed.
 *
 * Starting a foreground service from the background can be refused (API 31+). Then nothing is lost: the worker logs it and finishes, and the
 * queue runs at the next app start or unlock, like any pending job.
 */
class RetryProcessingWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = context.applicationContext
        try {
            MemoryService.resumePending(app)
            AppLog.i(TAG, "retry_processing", LogCtx.NONE)
        } catch (t: Throwable) {
            if (refused(t)) {
                AppLog.w(MemoryService.TAG_JOBS, "job_deferred", LogCtx.NONE, "code" to "fgs_start_refused", "error" to t.javaClass.simpleName)
            } else {
                AppLog.e(TAG, "retry_processing_failed", LogCtx.NONE, t)
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RetryProcessing"
        private const val WORK_NAME = "processing_retry"

        /** True for the system refusing a foreground service start from the background. */
        internal fun refused(t: Throwable): Boolean =
            Build.VERSION.SDK_INT >= 31 && t is android.app.ForegroundServiceStartNotAllowedException

        /** One retry at a time: a later deferral replaces the earlier one. */
        fun schedule(context: Context, retryInMs: Long) {
            val request = OneTimeWorkRequestBuilder<RetryProcessingWorker>().setInitialDelay(retryInMs.coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            AppLog.i(TAG, "retry_scheduled", LogCtx.NONE, "retry_in_ms" to retryInMs)
        }
    }
}
