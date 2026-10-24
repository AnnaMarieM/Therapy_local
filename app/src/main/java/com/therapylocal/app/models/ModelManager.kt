package com.therapylocal.app.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class ModelState { Missing, Downloading, Verifying, Ready, Failed }

data class ModelStatus(
    val spec: ModelSpec,
    val state: ModelState,
    val done: Long = 0,
    val total: Long = spec.totalBytes,
    val error: String? = null,
)

/** Makes sure each requested model is on disk, downloading it in the background (resumable) when it is not. */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    private val workManager = WorkManager.getInstance(context)
    private val _status = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val status: StateFlow<Map<String, ModelStatus>> = _status.asStateFlow()
    private val watching = HashSet<String>()

    fun ensure(spec: ModelSpec) {
        if (spec.isReady(context)) {
            set(ModelStatus(spec, ModelState.Ready, spec.totalBytes))
            return
        }
        set(ModelStatus(spec, ModelState.Downloading, 0))
        AppLog.i(TAG, "model_needed", LogCtx.NONE, "model" to spec.id, "size_mb" to (spec.totalBytes shr 20))
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_SPEC, spec.id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ModelDownloadWorker.uniqueName(spec), ExistingWorkPolicy.KEEP, request)
        if (watching.add(spec.id)) watch(spec)
    }

    fun retry(spec: ModelSpec) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(spec))
        watching.remove(spec.id)
        ensure(spec)
    }

    fun isReady(spec: ModelSpec) = _status.value[spec.id]?.state == ModelState.Ready || spec.isReady(context)

    private fun watch(spec: ModelSpec) {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.uniqueName(spec)).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val verifying = p.getString(ModelDownloadWorker.KEY_PHASE) == ModelDownloadWorker.PHASE_VERIFYING
                        set(
                            ModelStatus(
                                spec,
                                if (verifying) ModelState.Verifying else ModelState.Downloading,
                                p.getLong(ModelDownloadWorker.KEY_DONE, 0),
                            ),
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> set(ModelStatus(spec, ModelState.Ready, spec.totalBytes))
                    WorkInfo.State.FAILED -> set(
                        ModelStatus(spec, ModelState.Failed, error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed"),
                    )
                    else -> Unit
                }
            }
        }
    }

    private fun set(s: ModelStatus) = _status.update { it + (s.spec.id to s) }

    companion object {
        const val TAG = "Models"
    }
}
