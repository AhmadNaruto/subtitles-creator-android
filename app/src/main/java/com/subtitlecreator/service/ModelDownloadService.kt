package com.subtitlecreator.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads GGML Whisper models from HuggingFace / custom URLs into app-private storage.
 */
class ModelDownloadService(private val context: Context) {

    @Serializable
    data class ModelSpec(
        val id: String,
        val displayName: String,
        val url: String,
        val sizeMb: Int,
        val fileName: String,
        val isCustom: Boolean = false
    )

    private val prefs = context.getSharedPreferences("whisper_models", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    val defaultModel: ModelSpec = BASE_Q5

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(spec: ModelSpec = defaultModel): File = File(modelsDir(), spec.fileName)

    fun isDownloaded(spec: ModelSpec = defaultModel): Boolean {
        val f = modelFile(spec)
        val minSize = if (spec.sizeMb > 0) spec.sizeMb * 900_000L else 5_000_000L // 5MB minimum check
        return f.exists() && f.length() > minSize
    }

    fun getModelById(id: String): ModelSpec {
        return getAllModels().firstOrNull { it.id == id } ?: defaultModel
    }

    fun getDownloadedModelIds(): Set<String> {
        return getAllModels().filter { isDownloaded(it) }.map { it.id }.toSet()
    }

    fun getAllModels(): List<ModelSpec> {
        return ALL_MODELS + getCustomModels()
    }

    fun getCustomModels(): List<ModelSpec> {
        val raw = prefs.getString("custom_models_json", null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ModelSpec>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun saveCustomModels(models: List<ModelSpec>) {
        val raw = json.encodeToString(models)
        prefs.edit().putString("custom_models_json", raw).apply()
    }

    /**
     * Fixes HuggingFace web URLs from '/blob/' to '/resolve/' so they directly download binary data.
     */
    fun normalizeModelUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.contains("huggingface.co") && url.contains("/blob/")) {
            url = url.replace("/blob/", "/resolve/")
        }
        return url
    }

    /**
     * Adds a user-specified custom model URL (e.g. from Hugging Face).
     */
    fun addCustomModel(rawUrl: String, customName: String? = null): ModelSpec {
        val url = normalizeModelUrl(rawUrl)
        val derived = url.substringAfterLast("/").substringBefore("?").ifEmpty { "custom_${System.currentTimeMillis()}.bin" }
        val fileName = if (derived.endsWith(".bin")) derived else "$derived.bin"
        val id = "custom_" + fileName.substringBeforeLast(".")
        val displayName = customName?.trim()?.ifEmpty { null } ?: "Custom ($fileName)"

        val spec = ModelSpec(
            id = id,
            displayName = displayName,
            url = url,
            sizeMb = 0,
            fileName = fileName,
            isCustom = true
        )

        val list = getCustomModels().toMutableList()
        list.removeAll { it.id == id }
        list.add(0, spec)
        saveCustomModels(list)
        return spec
    }

    fun removeCustomModel(id: String) {
        val list = getCustomModels().toMutableList()
        val spec = list.firstOrNull { it.id == id }
        if (spec != null) {
            list.remove(spec)
            saveCustomModels(list)
            runCatching { modelFile(spec).delete() }
        }
    }

    suspend fun download(
        spec: ModelSpec = defaultModel,
        onProgress: (downloadedMb: Int, totalMb: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(spec)
        if (isDownloaded(spec)) {
            Log.i(TAG, "Model already present: ${target.name}")
            return@withContext target
        }
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()

        val req = Request.Builder().url(spec.url).build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Download gagal: HTTP ${resp.code}. Periksa link URL model." }
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: (if (spec.sizeMb > 0) spec.sizeMb * 1_000_000L else 0L)

            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    var lastMb = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        sum += read
                        val mb = (sum / 1_000_000L).toInt()
                        if (mb != lastMb) {
                            lastMb = mb
                            val totalMb = if (total > 0) (total / 1_000_000L).toInt() else 0
                            onProgress(mb, totalMb)
                        }
                    }
                }
            }
        }
        check(tmp.renameTo(target)) { "Rename ${tmp.name} → ${target.name} failed" }

        // Update actual size if custom
        if (spec.isCustom) {
            val actualMb = (target.length() / 1_000_000L).toInt().coerceAtLeast(1)
            val updated = spec.copy(sizeMb = actualMb)
            val list = getCustomModels().map { if (it.id == spec.id) updated else it }
            saveCustomModels(list)
        }

        Log.i(TAG, "Downloaded: ${target.name} (${target.length() / 1024 / 1024} MB)")
        target
    }

    companion object {
        private const val TAG = "ModelDownload"

        val TINY_Q5 = ModelSpec(
            id = "tiny-q5_1",
            displayName = "Whisper tiny (Q5_1, ~31 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            sizeMb = 31,
            fileName = "ggml-tiny-q5_1.bin"
        )

        val BASE_Q5 = ModelSpec(
            id = "base-q5_1",
            displayName = "Whisper base (Q5_1, ~57 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeMb = 57,
            fileName = "ggml-base-q5_1.bin"
        )

        val SMALL_Q5 = ModelSpec(
            id = "small-q5_1",
            displayName = "Whisper small (Q5_1, ~182 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            sizeMb = 182,
            fileName = "ggml-small-q5_1.bin"
        )

        val MEDIUM_Q5 = ModelSpec(
            id = "medium-q5",
            displayName = "Whisper medium (Q5_0, ~515 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            sizeMb = 515,
            fileName = "ggml-medium-q5_0.bin"
        )

        val LARGE_V3_TURBO_Q5 = ModelSpec(
            id = "large-v3-turbo-q5",
            displayName = "Whisper large-v3-turbo (Q5_0, ~574 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            sizeMb = 574,
            fileName = "ggml-large-v3-turbo-q5_0.bin"
        )

        val ALL_MODELS = listOf(
            TINY_Q5,
            BASE_Q5,
            SMALL_Q5,
            MEDIUM_Q5,
            LARGE_V3_TURBO_Q5
        )
    }
}
