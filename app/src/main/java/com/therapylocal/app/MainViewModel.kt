package com.therapylocal.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class Stage { Downloading, Verifying, LoadingEngine, Ready, Failed }

data class UiState(
    val stage: Stage = Stage.Downloading,
    val downloaded: Long = 0,
    val total: Long = ModelConfig.SIZE_BYTES,
    val backend: String = "",
    val error: String? = null,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val transcript: String = "",
    val lastTranscribeMs: Long? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val workManager = WorkManager.getInstance(app)
    private val llm = LlmEngine(app)
    private val recorder = AudioRecorder()
    private var engineLoading = false

    init {
        if (ModelConfig.isReady(app)) loadEngine() else startDownload()
    }

    fun startDownload() {
        _state.update { it.copy(stage = Stage.Downloading, error = null) }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ModelDownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val verifying = p.getString(ModelDownloadWorker.KEY_PHASE) == ModelDownloadWorker.PHASE_VERIFYING
                        _state.update {
                            it.copy(
                                stage = if (verifying) Stage.Verifying else Stage.Downloading,
                                downloaded = p.getLong(ModelDownloadWorker.KEY_DONE, it.downloaded),
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> loadEngine()
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(stage = Stage.Failed, error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed")
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun loadEngine() {
        if (engineLoading || _state.value.stage == Stage.Ready) return
        engineLoading = true
        _state.update { it.copy(stage = Stage.LoadingEngine, error = null) }
        viewModelScope.launch {
            try {
                val backend = llm.load(ModelConfig.file(getApplication()).path)
                _state.update { it.copy(stage = Stage.Ready, backend = backend) }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine load failed", t)
                _state.update { it.copy(stage = Stage.Failed, error = t.message) }
            } finally {
                engineLoading = false
            }
        }
    }

    fun retry() {
        if (ModelConfig.isReady(getApplication())) loadEngine() else startDownload()
    }

    fun startRecording() {
        val s = _state.value
        if (s.stage != Stage.Ready || s.recording || s.transcribing) return
        try {
            recorder.start(onLimit = { stopRecording() })
            _state.update { it.copy(recording = true, transcript = "", error = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = "Recording failed: ${t.message}") }
        }
    }

    fun stopRecording() {
        if (!_state.value.recording) return
        val wav = recorder.stop()
        _state.update { it.copy(recording = false) }
        if (wav == null) {
            _state.update { it.copy(error = "Too short, hold the button while speaking") }
            return
        }
        _state.update { it.copy(transcribing = true, transcript = "") }
        val started = System.currentTimeMillis()
        viewModelScope.launch {
            llm.transcribe(wav)
                .catch { t ->
                    Log.e(TAG, "Transcription failed", t)
                    _state.update { it.copy(error = "Transcription failed: ${t.message}") }
                }
                .collect { chunk -> _state.update { it.copy(transcript = it.transcript + chunk) } }
            _state.update { it.copy(transcribing = false, lastTranscribeMs = System.currentTimeMillis() - started) }
            Log.i(TAG, "Transcript: ${_state.value.transcript}")
        }
    }

    override fun onCleared() {
        llm.close()
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}
