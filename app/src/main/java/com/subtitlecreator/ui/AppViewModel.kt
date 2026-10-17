package com.subtitlecreator.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.service.ModelDownloadService
import com.subtitlecreator.service.PipelineService
import com.subtitlecreator.service.PipelineStore
import com.subtitlecreator.util.FileUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI-layer wrapper over [PipelineStore]. Long-running operations are
 * delegated to [PipelineService] (foreground service) so they survive the app
 * going to background or the screen turning off.
 */
typealias UiState = PipelineStore.UiState

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val modelDl = ModelDownloadService(app)
    val translator = com.subtitlecreator.service.TranslationService()

    val state: StateFlow<PipelineStore.UiState> = PipelineStore.state

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        val curId = PipelineStore.current().selectedModelId
        val spec = modelDl.getModelById(curId)
        val downloaded = modelDl.getDownloadedModelIds()
        PipelineStore.update {
            it.copy(
                modelReady = modelDl.isDownloaded(spec),
                downloadedModelIds = downloaded
            )
        }
    }

    fun selectModel(modelId: String) {
        val spec = modelDl.getModelById(modelId)
        val isReady = modelDl.isDownloaded(spec)
        PipelineStore.update {
            it.copy(
                selectedModelId = modelId,
                modelReady = isReady,
                downloadedModelIds = modelDl.getDownloadedModelIds()
            )
        }
    }

    fun addCustomModel(url: String, name: String? = null) {
        val spec = modelDl.addCustomModel(url, name)
        selectModel(spec.id)
    }

    fun removeCustomModel(id: String) {
        modelDl.removeCustomModel(id)
        if (state.value.selectedModelId == id) {
            selectModel(ModelDownloadService.BASE_Q5.id)
        } else {
            refreshModelStatus()
        }
    }

    fun downloadModel(modelId: String? = null) {
        val target = modelId ?: state.value.selectedModelId
        startService(action = PipelineService.ACTION_DOWNLOAD, modelId = target)
    }

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            val file = FileUtils.copyUriToAppStorage(getApplication(), uri, "pick_${System.currentTimeMillis()}.mp4")
            val dims = FileUtils.videoDimensions(file)
            PipelineStore.update {
                it.copy(
                    videoFile = file,
                    videoWidthPx = dims.widthPx,
                    videoHeightPx = dims.heightPx,
                    subtitles = emptyList(),
                    exportedFile = null,
                    savedUri = null,
                    error = null
                )
            }
        }
    }

    fun setSourceLanguage(lang: String) {
        PipelineStore.update { it.copy(sourceLanguage = lang) }
    }

    fun startTranscription() {
        val video = state.value.videoFile ?: return
        startService(
            action = PipelineService.ACTION_TRANSCRIBE,
            videoPath = video.absolutePath,
            language = state.value.sourceLanguage
        )
    }

    fun updateSubtitle(index: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null) {
        PipelineStore.update { s ->
            val list = s.subtitles.toMutableList()
            val cur = list.getOrNull(index) ?: return@update s
            list[index] = cur.copy(
                text = text ?: cur.text,
                startMs = startMs ?: cur.startMs,
                endMs = endMs ?: cur.endMs
            )
            s.copy(subtitles = list)
        }
    }

    fun deleteSubtitle(index: Int) {
        PipelineStore.update { s ->
            val list = s.subtitles.toMutableList()
            if (index in list.indices) list.removeAt(index)
            s.copy(subtitles = list)
        }
    }

    fun addSubtitle(afterIndex: Int) {
        PipelineStore.update { s ->
            val list = s.subtitles.toMutableList()
            val prev = list.getOrNull(afterIndex)
            val start = prev?.endMs ?: 0L
            list.add(afterIndex + 1, Subtitle("new", start, start + 800))
            s.copy(subtitles = list)
        }
    }

    fun translateSubtitles(targetLang: String, onDone: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val currentSubs = state.value.subtitles
        if (currentSubs.isEmpty()) return

        viewModelScope.launch {
            PipelineStore.update {
                it.copy(
                    isTranslating = true,
                    translationProgress = 0,
                    translationTotal = currentSubs.size
                )
            }
            try {
                val translated = translator.translateSubtitles(currentSubs, targetLang) { current, total ->
                    PipelineStore.update {
                        it.copy(translationProgress = current, translationTotal = total)
                    }
                }
                PipelineStore.update {
                    it.copy(
                        subtitles = translated,
                        isTranslating = false
                    )
                }
                onDone()
            } catch (t: Throwable) {
                PipelineStore.update { it.copy(isTranslating = false) }
                onError(t.message ?: "Gagal menerjemahkan subtitle")
            }
        }
    }

    fun export() {
        val video = state.value.videoFile ?: return
        if (state.value.subtitles.isEmpty()) return
        startService(PipelineService.ACTION_EXPORT, video.absolutePath)
    }

    fun cancel() {
        startService(PipelineService.ACTION_CANCEL)
    }

    fun setFontSize(size: Int) {
        PipelineStore.update { it.copy(subtitleFontSize = size.coerceIn(24, 200)) }
    }

    fun setSubtitlePosition(fraction: Float) {
        PipelineStore.update { it.copy(subtitlePositionFraction = fraction.coerceIn(0f, 0.95f)) }
    }

    fun clearError() = PipelineStore.update { it.copy(error = null) }

    private fun startService(
        action: String,
        videoPath: String? = null,
        modelId: String? = null,
        language: String? = null
    ) {
        val app = getApplication<Application>()
        val intent = Intent(app, PipelineService::class.java).apply {
            this.action = action
            videoPath?.let { putExtra(PipelineService.EXTRA_VIDEO_PATH, it) }
            modelId?.let { putExtra(PipelineService.EXTRA_MODEL_ID, it) }
            language?.let { putExtra(PipelineService.EXTRA_LANGUAGE, it) }
        }
        ContextCompat.startForegroundService(app, intent)
    }
}
