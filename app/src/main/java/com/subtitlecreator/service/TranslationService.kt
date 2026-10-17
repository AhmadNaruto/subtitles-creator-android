package com.subtitlecreator.service

import android.util.Log
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Service to translate subtitles using Google Translate Web (client=dict-chrome-ex).
 * Free, fast, and does not require a paid API key.
 */
class TranslationService {

    data class Language(val code: String, val name: String, val flag: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun translateSubtitles(
        subtitles: List<Subtitle>,
        targetLang: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        if (subtitles.isEmpty()) return@withContext emptyList()

        val result = mutableListOf<Subtitle>()
        val chunkSize = 25 // 25 subtitles per request to keep GET URL under safe limits
        val chunks = subtitles.chunked(chunkSize)

        for ((chunkIdx, chunk) in chunks.withIndex()) {
            val delimiter = "\n"
            val combinedText = chunk.joinToString(delimiter) { it.text.trim().ifEmpty { "-" } }

            val translatedText = translateText(combinedText, targetLang)
            val translatedLines = translatedText.split(delimiter)

            for (i in chunk.indices) {
                val orig = chunk[i]
                val translated = translatedLines.getOrNull(i)?.trim()?.takeIf { it != "-" } ?: orig.text
                result.add(orig.copy(text = translated))
            }

            onProgress(result.size, subtitles.size)

            // Small polite delay between batches
            if (chunkIdx < chunks.size - 1) {
                delay(120)
            }
        }

        result
    }

    private fun translateText(text: String, targetLang: String): String {
        val url = "https://clients5.google.com/translate_a/t".toHttpUrl().newBuilder()
            .addQueryParameter("client", "dict-chrome-ex")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLang)
            .addQueryParameter("q", text)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .build()

        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: Gagal menerjemahkan teks" }
            val bodyStr = resp.body?.string().orEmpty()
            return parseGoogleTranslateResponse(bodyStr, text)
        }
    }

    private fun parseGoogleTranslateResponse(jsonStr: String, fallback: String): String {
        return try {
            val root = JSONArray(jsonStr)
            val first = root.getJSONArray(0)
            first.getString(0)
        } catch (t: Throwable) {
            Log.w(TAG, "Gagal parse respons translasi: $jsonStr", t)
            fallback
        }
    }

    companion object {
        private const val TAG = "TranslationService"

        val SUPPORTED_LANGUAGES = listOf(
            Language("id", "Indonesia", "🇮🇩"),
            Language("en", "Inggris (English)", "🇬🇧"),
            Language("ja", "Jepang (日本語)", "🇯🇵"),
            Language("ko", "Korea (한국어)", "🇰🇷"),
            Language("ar", "Arab (العربية)", "🇸🇦"),
            Language("zh-CN", "Mandarin (简体中文)", "🇨🇳"),
            Language("es", "Spanyol (Español)", "🇪🇸"),
            Language("fr", "Prancis (Français)", "🇫🇷"),
            Language("de", "Jerman (Deutsch)", "🇩🇪"),
            Language("ru", "Rusia (Русский)", "🇷🇺")
        )
    }
}
