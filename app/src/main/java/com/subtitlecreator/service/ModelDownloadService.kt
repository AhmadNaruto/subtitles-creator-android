package com.subtitlecreator.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads GGML Whisper models from HuggingFace into app-private storage.
 *
 * Default model: large-v3-turbo Q5_0 (~574 MB) — best accuracy/speed ratio on S23 Ultra.
 * Multilingual (FR + EN work identically). Change MODEL_URL to switch models.
 */
class ModelDownloadService(private val context: Context) {

    data class ModelSpec(
        val id: String,
        val displayName: String,
        val url: String,
        val sizeMb: Int,
        val fileName: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    // base Q5 is recommended default for mobile: very fast, light (~57 MB), and accurate enough.
    val defaultModel: ModelSpec = BASE_Q5

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(spec: ModelSpec = defaultModel): File = File(modelsDir(), spec.fileName)

    fun isDownloaded(spec: ModelSpec = defaultModel): Boolean {
        val f = modelFile(spec)
        return f.exists() && f.length() > spec.sizeMb * 900_000L // rough sanity check
    }

    fun getModelById(id: String): ModelSpec {
        return ALL_MODELS.firstOrNull { it.id == id } ?: defaultModel
    }

    fun getDownloadedModelIds(): Set<String> {
        return ALL_MODELS.filter { isDownloaded(it) }.map { it.id }.toSet()
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
            check(resp.isSuccessful) { "Download failed ${resp.code}" }
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: (spec.sizeMb * 1_000_000L)

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
                            onProgress(mb, (total / 1_000_000L).toInt())
                        }
                    }
                }
            }
        }
        check(tmp.renameTo(target)) { "Rename ${tmp.name} → ${target.name} failed" }
        Log.i(TAG, "Downloaded: ${target.name} (${target.length() / 1024 / 1024} MB)")
        target
    }

    companion object {
        private const val TAG = "ModelDownload"

        // 1. Ultra lightweight & fastest — best for lower-end phones
        val TINY_Q5 = ModelSpec(
            id = "tiny-q5_1",
            displayName = "Whisper tiny (Q5_1, ~31 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            sizeMb = 31,
            fileName = "ggml-tiny-q5_1.bin"
        )

        // 2. Recommended default — fast & great balance for mobile
        val BASE_Q5 = ModelSpec(
            id = "base-q5_1",
            displayName = "Whisper base (Q5_1, ~57 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeMb = 57,
            fileName = "ggml-base-q5_1.bin"
        )

        // 3. High quality
        val SMALL_Q5 = ModelSpec(
            id = "small-q5_1",
            displayName = "Whisper small (Q5_1, ~182 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            sizeMb = 182,
            fileName = "ggml-small-q5_1.bin"
        )

        // 4. Very high precision
        val MEDIUM_Q5 = ModelSpec(
            id = "medium-q5",
            displayName = "Whisper medium (Q5_0, ~515 MB)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            sizeMb = 515,
            fileName = "ggml-medium-q5_0.bin"
        )

        // 5. Peak accuracy for flagship devices
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
