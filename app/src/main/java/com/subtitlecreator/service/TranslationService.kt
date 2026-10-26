package com.subtitlecreator.service

import android.util.Log
import com.subtitlecreator.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Service to translate subtitles using Google Translate Web (client=dict-chrome-ex)
 * with CapCut-style phrase grouping to ensure 100% natural grammatical context and idiomatic fluency.
 */
class TranslationService {

    data class Language(val code: String, val name: String, val flag: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class PhraseBlock(
        val originalSubs: List<Subtitle>,
        val sentenceText: String,
        val startMs: Long,
        val endMs: Long
    )

    suspend fun translateSubtitles(
        subtitles: List<Subtitle>,
        targetLang: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        if (subtitles.isEmpty()) return@withContext emptyList()

        // 1. Group word-level subtitles into natural spoken phrases/clauses
        val phrases = groupIntoPhrases(subtitles)
        val result = mutableListOf<Subtitle>()
        val batchSize = 15 // 15 full sentences per request for fast, reliable batching
        val phraseChunks = phrases.chunked(batchSize)

        var processedOriginalCount = 0

        for ((chunkIdx, chunk) in phraseChunks.withIndex()) {
            val sentences = chunk.map { it.sentenceText }
            val translatedSentences = translateBatchPost(sentences, targetLang)

            for (i in chunk.indices) {
                val phrase = chunk[i]
                val translatedSentence = translatedSentences.getOrNull(i)?.ifEmpty { null } ?: phrase.sentenceText
                val distributedSubs = distributeWordsTiming(phrase, translatedSentence, targetLang)
                result.addAll(distributedSubs)

                processedOriginalCount += phrase.originalSubs.size
                onProgress(processedOriginalCount.coerceAtMost(subtitles.size), subtitles.size)
            }

            // Small polite delay between batches to avoid rate limiting
            if (chunkIdx < phraseChunks.size - 1) {
                delay(120)
            }
        }

        onProgress(subtitles.size, subtitles.size)
        result
    }

    /**
     * Groups word-level subtitles into natural sentence clauses based on pauses and punctuation.
     */
    private fun groupIntoPhrases(subtitles: List<Subtitle>): List<PhraseBlock> {
        if (subtitles.isEmpty()) return emptyList()
        val blocks = mutableListOf<PhraseBlock>()
        val currentGroup = mutableListOf<Subtitle>()

        for (sub in subtitles) {
            val txt = sub.text.trim()
            if (txt.isEmpty()) continue

            if (currentGroup.isEmpty()) {
                currentGroup.add(sub)
            } else {
                val prev = currentGroup.last()
                val gap = sub.startMs - prev.endMs
                val isPunctuation = prev.text.endsWith(".") || prev.text.endsWith("?") ||
                                    prev.text.endsWith("!") || prev.text.endsWith(";")
                val groupDuration = sub.endMs - currentGroup.first().startMs

                // Break into a new phrase if:
                // - Natural silence gap (> 650 ms)
                // - Sentence ending punctuation
                // - Group has >= 8 words or total duration > 5.5 seconds
                if (gap > 650 || isPunctuation || currentGroup.size >= 8 || groupDuration > 5500) {
                    blocks.add(
                        PhraseBlock(
                            originalSubs = currentGroup.toList(),
                            sentenceText = currentGroup.joinToString(" ") { it.text.trim() },
                            startMs = currentGroup.first().startMs,
                            endMs = maxOf(currentGroup.first().startMs + 200L, currentGroup.last().endMs)
                        )
                    )
                    currentGroup.clear()
                }
                currentGroup.add(sub)
            }
        }

        if (currentGroup.isNotEmpty()) {
            blocks.add(
                PhraseBlock(
                    originalSubs = currentGroup.toList(),
                    sentenceText = currentGroup.joinToString(" ") { it.text.trim() },
                    startMs = currentGroup.first().startMs,
                    endMs = maxOf(currentGroup.first().startMs + 200L, currentGroup.last().endMs)
                )
            )
        }

        return blocks
    }

    /**
     * Sends batch of sentences via HTTP POST FormBody (with multiple 'q' fields).
     */
    private fun translateBatchPost(sentences: List<String>, targetLang: String): List<String> {
        if (sentences.isEmpty()) return emptyList()

        val formBuilder = FormBody.Builder()
        for (s in sentences) {
            formBuilder.add("q", s.ifEmpty { "-" })
        }
        val formBody = formBuilder.build()

        val url = "https://clients5.google.com/translate_a/t".toHttpUrl().newBuilder()
            .addQueryParameter("client", "dict-chrome-ex")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLang)
            .build()

        val req = Request.Builder()
            .url(url)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .build()

        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: Gagal menerjemahkan teks" }
            val bodyStr = resp.body?.string().orEmpty()
            return parseGoogleTranslateBatchResponse(bodyStr, sentences)
        }
    }

    /**
     * Parses the JSON array response where each index corresponds directly to an input sentence.
     */
    private fun parseGoogleTranslateBatchResponse(jsonStr: String, fallbacks: List<String>): List<String> {
        val out = mutableListOf<String>()
        try {
            val root = JSONArray(jsonStr)
            for (i in 0 until root.length()) {
                val item = root.optJSONArray(i)
                val translated = item?.optString(0).orEmpty().trim()
                out.add(translated.ifEmpty { fallbacks.getOrElse(i) { "" } })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Gagal parse respons translasi batch: $jsonStr", t)
        }
        while (out.size < fallbacks.size) {
            out.add(fallbacks[out.size])
        }
        return out
    }

    /**
     * Distributes the translated sentence into individual words/segments mapped smoothly
     * across the phrase's exact start and end timestamps (CapCut style).
     */
    private fun distributeWordsTiming(phrase: PhraseBlock, translatedSentence: String, targetLang: String): List<Subtitle> {
        val raw = translatedSentence.trim()
        if (raw.isEmpty()) return phrase.originalSubs

        val words = if ((targetLang == "ja" || targetLang == "zh-CN") && !raw.contains(" ")) {
            // CJK segmentation by punctuation or chunks of 2-4 characters
            splitCjkSentence(raw)
        } else {
            raw.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        }

        if (words.isEmpty()) return phrase.originalSubs

        val startMs = phrase.startMs
        val endMs = phrase.endMs
        val totalDuration = maxOf(words.size * 150L, endMs - startMs)

        // Character weighting: longer words receive proportionally more screen time
        val charWeights = words.map { maxOf(2, it.length) }
        val totalWeight = charWeights.sum()

        val result = mutableListOf<Subtitle>()
        var currentStart = startMs

        for (i in words.indices) {
            val word = words[i]
            val weight = charWeights[i]
            val wordDuration = if (i == words.lastIndex) {
                maxOf(150L, (startMs + totalDuration) - currentStart)
            } else {
                ((weight.toDouble() / totalWeight.toDouble()) * totalDuration).toLong().coerceAtLeast(150L)
            }

            val wordEnd = currentStart + wordDuration
            result.add(Subtitle(text = word, startMs = currentStart, endMs = wordEnd))
            currentStart = wordEnd
        }

        return result
    }

    /**
     * Chunks Japanese / Chinese phrases without spaces into readable segments.
     */
    private fun splitCjkSentence(text: String): List<String> {
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (ch == '。' || ch == '、' || ch == '！' || ch == '？' || buffer.length >= 4) {
                parts.add(buffer.toString())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) parts.add(buffer.toString())
        return parts.ifEmpty { listOf(text) }
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
