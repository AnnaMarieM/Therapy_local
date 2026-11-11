package com.therapylocal.app.models

import android.app.ActivityManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * [Confirm]: the download would go over mobile data (a metered network), so it waits for the user's answer (issue #55).
 * [WaitingForWifi]: the download is queued for an unmetered network and none is there. [Declined]: the user said "Not now"; nothing is
 * queued until they tap again (or the next start of the app on Wi-Fi).
 */
enum class SetupState { Checking, Downloading, Ready, Failed, Confirm, WaitingForWifi, Declined }

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
    private const val PREF_WIFI_ONLY = "model_download_wifi_only"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow(SetupProgress(SetupState.Checking))
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()
    private var observing = false

    /**
     * Distinct specs the app needs: embeddings, Canary (one multilingual model serves every client language), the speaker-diarization pair
     * (docs/AD/AD-06.md) and the memory model.
     */
    fun required(context: Context): List<ModelSpec> = essential(context) + ModelRegistry.DIARIZATION

    /**
     * What the app cannot work without: [required] minus the speaker-diarization pair. That one is downloaded like the others, but sessions do
     * not wait for it ([isReady] ignores it): an install from before it existed keeps processing its queue on the chunk path (docs/AD/AD-06.md,
     * `diarization_skipped reason=model_missing`) and gets the model in the background.
     */
    fun essential(context: Context): List<ModelSpec> {
        val base = listOf(ModelRegistry.EMBED, ModelRegistry.CANARY_1B_V2, gemma(context))
        // An optimised device (AD-09) also fetches the accelerator-specific builds of the models that have one for its SoC; any other device fetches nothing extra.
        return base + ModelRegistry.backendSpecsFor(base.map { it.id }, NpuRuntime.deviceSoc())
    }

    fun gemmaVariant(context: Context): GemmaVariant = ModelChoice.resolve(prefs(context).getString(ModelChoice.PREF, null))

    /** The Gemma build the settings choose, for the memory LLM. */
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
        val mem = ActivityManager.MemoryInfo().also { m -> context.getSystemService(ActivityManager::class.java)?.let { runCatching { it.getMemoryInfo(m) } } }
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

    fun isReady(context: Context): Boolean = com.therapylocal.app.engine.LabWiring.ready(essential(context).all { it.isReady(context) })

    /**
     * Called on every launch and every [com.therapylocal.app.MainViewModel] prepare. A download already queued is left as it is (with the
     * network the user chose for it); a new one starts by itself only for an unmetered network, on mobile data the user is asked first
     * ([DownloadNetwork], issue #55).
     */
    fun start(context: Context) {
        val app = context.applicationContext
        // An open question, a "Not now" or a download in flight stays as it is: this is called again on every prepare.
        when (_progress.value.state) {
            SetupState.Confirm, SetupState.Declined, SetupState.Downloading, SetupState.WaitingForWifi -> return
            SetupState.Failed -> _progress.value = SetupProgress(SetupState.Checking)
            SetupState.Checking, SetupState.Ready -> {}
        }
        scope.launch {
            if (isReady(app)) {
                _progress.value = SetupProgress(SetupState.Ready, totalBytes(app), totalBytes(app))
                // An install from before the diarization pair: it is fetched in the background and the app stays usable meanwhile.
                // Nobody is waiting for it, so it is never worth a question: it comes on the next unmetered network.
                if (!ModelRegistry.DIARIZATION.isReady(app) && !queued(app)) enqueue(app, ExistingWorkPolicy.KEEP, wifiOnly = true)
                return@launch
            }
            if (!queued(app)) {
                if (DownloadNetwork.mustAsk(DownloadNetwork.current(app))) return@launch ask(app)
                enqueue(app, ExistingWorkPolicy.KEEP, wifiOnly = true)
            }
            observe(app)
        }
    }

    /** "Try again", a model switch, or a tap on "Setup paused": starts over, asking first on mobile data. */
    fun retry(context: Context) {
        val app = context.applicationContext
        _progress.value = SetupProgress(SetupState.Checking)
        scope.launch {
            if (isReady(app)) {
                _progress.value = SetupProgress(SetupState.Ready, totalBytes(app), totalBytes(app))
                return@launch
            }
            if (DownloadNetwork.mustAsk(DownloadNetwork.current(app))) return@launch ask(app)
            enqueue(app, ExistingWorkPolicy.REPLACE, wifiOnly = true)
            observe(app)
        }
    }

    /** Asks whether to use mobile data now, whatever the network: the tap on "Waiting for Wi-Fi · Use mobile data". */
    fun askMobileData(context: Context) {
        val app = context.applicationContext
        scope.launch { if (!isReady(app)) ask(app) }
    }

    /** The answers to [SetupState.Confirm]. "Download now" uses any connection until the files are on disk, Wi-Fi drops or not. */
    fun downloadNow(context: Context) = answer(context, DownloadNetwork.Choice.Now)

    /** Queues the download for the next unmetered network; a download cut off when Wi-Fi drops resumes there from its partial file. */
    fun waitForWifi(context: Context) = answer(context, DownloadNetwork.Choice.Wifi)

    /** Nothing new is queued; the home line and the settings offer to try again. */
    fun notNow(context: Context) = answer(context, DownloadNetwork.Choice.NotNow)

    private fun answer(context: Context, choice: DownloadNetwork.Choice) {
        val app = context.applicationContext
        AppLog.i(TAG, "download_network_choice", LogCtx.NONE, "choice" to choice.key)
        if (choice == DownloadNetwork.Choice.NotNow) {
            _progress.value = _progress.value.copy(state = SetupState.Declined, etaSeconds = null, message = null)
            // A download already queued for Wi-Fi stays queued; otherwise nothing downloads until the user asks again.
            scope.launch {
                if (!queued(app)) return@launch
                val p = _progress.value
                if (p.state == SetupState.Declined) _progress.compareAndSet(p, p.copy(state = SetupState.WaitingForWifi))
            }
            return
        }
        _progress.value = SetupProgress(SetupState.Checking)
        scope.launch {
            enqueue(app, ExistingWorkPolicy.REPLACE, wifiOnly = choice == DownloadNetwork.Choice.Wifi)
            observe(app)
        }
    }

    /** Shows the question with the size still to download (the bytes on disk, partial files included, are not counted again). */
    private fun ask(context: Context) {
        val total = totalBytes(context)
        val missing = required(context).sumOf { it.missingBytes(context) }
        AppLog.i(TAG, "download_needs_consent", LogCtx.NONE, "network" to "metered", "missing_mb" to (missing shr 20))
        _progress.value = SetupProgress(SetupState.Confirm, (total - missing).coerceAtLeast(0), total)
    }

    /** A download is queued or running (its network was chosen when it was queued). */
    private suspend fun queued(context: Context): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { !it.state.isFinished }

    /** Whether the queued download waits for an unmetered network (what [enqueue] last chose). */
    private fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(PREF_WIFI_ONLY, false)

    private fun totalBytes(context: Context) = required(context).sumOf { it.totalBytes }

    /**
     * [wifiOnly]: the work runs only on an unmetered network. WorkManager stops it when that goes away (the partial file stays, see
     * [ResumableDownload]) and runs it again, resuming, once one is back.
     */
    private fun enqueue(context: Context, policy: ExistingWorkPolicy, wifiOnly: Boolean) {
        val missing = required(context).sumOf { it.missingBytes(context) }
        AppLog.i(TAG, "setup_needed", LogCtx.NONE, "missing_mb" to (missing shr 20), "wifi_only" to wifiOnly)
        prefs(context).edit().putBoolean(PREF_WIFI_ONLY, wifiOnly).apply()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(DownloadNetwork.networkType(wifiOnly)).build())
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
                // The question (or the "Not now") stays until the user answers; the answer queues new work and this picks it up.
                if (_progress.value.state == SetupState.Confirm || _progress.value.state == SetupState.Declined) return@collect
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
                        if (DownloadNetwork.waitingForWifi(wifiOnly(context), DownloadNetwork.current(context))) last.copy(state = SetupState.WaitingForWifi, etaSeconds = null, message = null)
                        else last.copy(state = SetupState.Downloading, etaSeconds = null, message = if (last.doneBytes == 0L) context.getString(R.string.setup_waiting_network) else last.message)
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
