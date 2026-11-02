package com.therapylocal.app.models

import android.app.ActivityManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.therapylocal.app.R
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SetupState { Checking, Downloading, Ready, Failed }

data class SetupProgress(val state: SetupState, val doneBytes: Long = 0, val totalBytes: Long = 0, val etaSeconds: Long? = null, val message: String? = null) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Owns "is the app ready": the AI files the app needs (embedding + speech + memory) and their one background download.
 * A single unique work ("model-setup") fetches everything missing under one notification.
 */
object ModelSetup {
    const val TAG = "Models"
    const val WORK_NAME = "model-setup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow(SetupProgress(SetupState.Checking))
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()
    private var observing = false

    /** Distinct specs the current settings need: embeddings, the speech engine, and the memory model. */
    fun required(context: Context): List<ModelSpec> {
        val prefs = prefs(context)
        val engine = prefs.getString("engine", "whisper") ?: "whisper"
        val size = prefs.getString("whisper_size", ModelRegistry.DEFAULT_WHISPER_SIZE) ?: ModelRegistry.DEFAULT_WHISPER_SIZE
        val gemma = gemma(context)
        val speech = if (engine == "gemma") listOf(gemma) else SpeechLanguages.inUse(context).map { ModelRegistry.whisperFor(size, it) }
        return (listOf(ModelRegistry.EMBED) + speech + gemma).distinct()
    }

    fun gemmaVariant(context: Context): GemmaVariant = ModelChoice.resolve(prefs(context).getString(ModelChoice.PREF, null))

    /** The Gemma build the settings choose, for the memory LLM and Gemma speech recognition. */
    fun gemma(context: Context): ModelSpec = ModelRegistry.gemma(gemmaVariant(context))

    /**
     * Switches the Gemma build and downloads it if missing. Jobs that need it wait (their precondition is [isReady]) rather than fail.
     * The other build's file is kept until [removeUnusedGemma].
     */
    fun selectGemma(context: Context, variant: GemmaVariant) {
        if (variant == gemmaVariant(context)) return
        prefs(context).edit().putString(ModelChoice.PREF, variant.key).apply()
        AppLog.i(TAG, "gemma_variant_changed", LogCtx.NONE, "variant" to variant.key)
        retry(context)
    }

    fun suggestedGemma(context: Context): GemmaVariant {
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return ModelChoice.suggested(mem.totalMem)
    }

    /** The Gemma build not in use, if any of its file (or a partial download) is on disk. */
    fun unusedGemma(context: Context): ModelSpec? =
        ModelRegistry.gemma(ModelChoice.other(gemmaVariant(context))).takeIf { spec -> spec.files.any { spec.file(context, it).exists() || spec.part(context, it).exists() } }

    /** Frees the unused build's disk space; it is downloaded again if chosen later. */
    fun removeUnusedGemma(context: Context) {
        val spec = unusedGemma(context) ?: return
        spec.files.forEach { spec.file(context, it).delete(); spec.part(context, it).delete() }
        AppLog.i(TAG, "gemma_removed", LogCtx.NONE, "model" to spec.id, "freed_mb" to (spec.totalBytes shr 20))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isReady(context: Context): Boolean = required(context).all { it.isReady(context) }

    fun start(context: Context) {
        val app = context.applicationContext
        if (_progress.value.state != SetupState.Ready) _progress.value = SetupProgress(SetupState.Checking)
        scope.launch {
            if (isReady(app)) {
                _progress.value = SetupProgress(SetupState.Ready, totalBytes(app), totalBytes(app))
                return@launch
            }
            enqueue(app, ExistingWorkPolicy.KEEP)
            observe(app)
        }
    }

    fun retry(context: Context) {
        val app = context.applicationContext
        _progress.value = SetupProgress(SetupState.Checking)
        scope.launch {
            if (isReady(app)) {
                _progress.value = SetupProgress(SetupState.Ready, totalBytes(app), totalBytes(app))
                return@launch
            }
            enqueue(app, ExistingWorkPolicy.REPLACE)
            observe(app)
        }
    }

    private fun totalBytes(context: Context) = required(context).sumOf { it.totalBytes }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val missing = required(context).sumOf { it.missingBytes(context) }
        AppLog.i(TAG, "setup_needed", LogCtx.NONE, "missing_mb" to (missing shr 20))
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // The downloader retries a dropped connection itself; this is only the last resort, so keep it short and linear (exponential grows to hours).
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private fun observe(context: Context) {
        synchronized(this) {
            if (observing) return
            observing = true
        }
        scope.launch {
            var last = SetupProgress(SetupState.Downloading, 0, totalBytes(context))
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                _progress.value = when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val total = p.getLong(ModelDownloadWorker.KEY_TOTAL, 0)
                        if (total <= 0) last.copy(state = SetupState.Downloading, message = null) else {
                            val eta = p.getLong(ModelDownloadWorker.KEY_ETA, -1)
                            val verifying = p.getString(ModelDownloadWorker.KEY_PHASE) == ModelDownloadWorker.PHASE_VERIFYING
                            SetupProgress(
                                SetupState.Downloading, p.getLong(ModelDownloadWorker.KEY_DONE, 0), total,
                                if (eta >= 0 && !verifying) eta else null, if (verifying) context.getString(R.string.setup_checking_files) else null,
                            ).also { last = it }
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> SetupProgress(SetupState.Ready, totalBytes(context), totalBytes(context)).also { last = it }
                    WorkInfo.State.FAILED -> SetupProgress(
                        SetupState.Failed, last.doneBytes, last.totalBytes,
                        message = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: context.getString(R.string.setup_failed_generic),
                    )
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        last.copy(state = SetupState.Downloading, etaSeconds = null, message = if (last.doneBytes == 0L) context.getString(R.string.setup_waiting_network) else last.message)
                    WorkInfo.State.CANCELLED -> _progress.value
                }
            }
        }
    }

    /** "850 MB" below 1 GB, otherwise "1.2 GB" (1024-based); the decimal mark follows [locale]. */
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String =
        if (bytes < 1L shl 30) String.format(locale, "%d MB", bytes shr 20) else String.format(locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    fun formatBytes(context: Context, bytes: Long): String = formatBytes(bytes, context.resources.configuration.locales[0])

    /** "about 5 min", "about 1 h 20 min", "less than a minute", in the app's language. */
    fun formatEta(context: Context, seconds: Long): String {
        val min = (seconds + 59) / 60
        return when {
            seconds < 60 -> context.getString(R.string.setup_eta_less_than_minute)
            min < 60 -> context.getString(R.string.setup_eta_minutes, min)
            else -> {
                val h = min / 60
                val m = min % 60
                if (m == 0L) context.getString(R.string.setup_eta_hours, h) else context.getString(R.string.setup_eta_hours_minutes, h, m)
            }
        }
    }
}
