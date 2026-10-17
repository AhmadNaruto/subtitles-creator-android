package com.subtitlecreator.service

import android.util.Log
import com.subtitlecreator.jni.WhisperLib
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.model.TranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Orchestrates model load → PCM decode → whisper.cpp call → post-processing.
 *
 * Word-timing strategy: whisper.cpp is invoked with split_on_word=true + max_len=1 so
 * each returned segment is one word with native timestamps. We then:
 *   1) merge compound fragments (punctuation, leading/trailing quotes) back into their neighbour
 *   2) enforce a minimum visible duration so subtitles don't flash
 */
class WhisperService {

    private var ctxPtr: Long = 0L
    private var loadedModelPath: String? = null

    fun ensureLoaded(modelFile: File) {
        if (ctxPtr != 0L && loadedModelPath == modelFile.absolutePath) return
        if (ctxPtr != 0L) {
            release()
        }
        require(modelFile.exists()) { "Model file missing: ${modelFile.absolutePath}" }
        ctxPtr = WhisperLib.init(modelFile.absolutePath)
        loadedModelPath = modelFile.absolutePath
        Log.i(TAG, "Loaded: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")
    }

    fun release() {
        if (ctxPtr != 0L) {
            WhisperLib.free(ctxPtr)
            ctxPtr = 0L
            loadedModelPath = null
        }
    }

    suspend fun transcribe(
        wav16kMono: File,
        language: String = "auto",
        nThreads: Int = 6,
        onStatusUpdate: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        check(ctxPtr != 0L) { "Model not loaded — call ensureLoaded() first" }

        onStatusUpdate("Membaca & decode data audio WAV...")
        val pcm = readWavPcmFloat(wav16kMono)
        val totalSec = (pcm.size / 16000.0).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        val durStr = if (min > 0) "${min}m ${sec}s" else "${sec}s"
        Log.i(TAG, "Decoded ${pcm.size} samples ($durStr)")
        onStatusUpdate("Menjalankan Whisper AI pada $durStr audio...")

        val raw = WhisperLib.transcribe(
            ctxPtr = ctxPtr,
            pcm = pcm,
            language = language,
            nThreads = nThreads,
            translate = false,
            onProgress = onProgress
        )

        // whisper's t0 marks where the word starts — trust it.
        // whisper's t1 often gets stretched to the next event (next word / end of
        // chunk), so the last word before a pause can occupy 5+ seconds of silence.
        // Cap each word's duration to a per-character budget.
        val merged = mergeCompoundFragments(raw)
        val sanitized = sanitizeAndCapTimestamps(merged)

        TranscriptionResult(
            language = language,
            subtitles = sanitized
        )
    }

    /** Decode a 16kHz mono PCM16 WAV file into a float32 array normalized to [-1, 1]. */
    private fun readWavPcmFloat(wav: File): FloatArray {
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)

            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(hb.getInt(0) == 0x46464952) { "Not a RIFF/WAV file" }
            val sampleRate = hb.getInt(24)
            val numChannels = hb.getShort(22).toInt()
            val bitsPerSample = hb.getShort(34).toInt()
            require(sampleRate == 16000) { "Expected 16kHz WAV, got ${sampleRate}Hz" }
            require(numChannels == 1) { "Expected mono WAV, got $numChannels channels" }
            require(bitsPerSample == 16) { "Expected PCM16, got $bitsPerSample bps" }

            val totalBytes = (raf.length() - 44).toInt()
            val totalSamples = totalBytes / 2
            val out = FloatArray(totalSamples)

            // Stream decode with small 32KB buffer to prevent heap memory exhaustion on long videos
            val chunkBytes = ByteArray(32768)
            val chunkBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleIdx = 0
            var remaining = totalBytes

            while (remaining > 0) {
                val toRead = minOf(remaining, chunkBytes.size)
                raf.readFully(chunkBytes, 0, toRead)
                chunkBuffer.position(0)
                val samplesInChunk = toRead / 2
                for (i in 0 until samplesInChunk) {
                    out[sampleIdx++] = chunkBuffer.short.toFloat() / 32768f
                }
                remaining -= toRead
            }
            return out
        }
    }

    /**
     * whisper.cpp can emit bare punctuation or leading-apostrophe fragments as their own
     * "word" when max_len=1. Glue those back onto their lexical neighbour.
     */
    private fun mergeCompoundFragments(raw: List<WhisperLib.RawSegment>): List<Subtitle> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Subtitle>(raw.size)
        for (seg in raw) {
            val txt = seg.text.trim()
            if (txt.isEmpty() || txt == "[BLANK_AUDIO]" || txt.startsWith("(") && txt.endsWith(")")) {
                continue
            }
            val isFragment = txt.all { !it.isLetterOrDigit() }
                || txt.startsWith("'")
                || txt.startsWith("-")
            if (isFragment && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out += last.copy(
                    text = (last.text + " " + txt).replace("  ", " ").trim(),
                    endMs = maxOf(last.endMs, seg.endMs)
                )
            } else {
                out += Subtitle(text = txt, startMs = seg.startMs, endMs = seg.endMs)
            }
        }
        return out
    }

    /**
     * Fix timing synchronization:
     * 1. ALWAYS TRUST `startMs` (t0) from Whisper! This is the exact moment speech sounds begin.
     *    Never shift startMs forward, as doing so causes subtitles to appear noticeably late.
     * 2. Whisper's `endMs` (t1) frequently stretches across long trailing pauses/silences until
     *    the next speech chunk. We cap `endMs` so words disappear after reasonable duration
     *    and never overlap the next spoken word.
     * 3. Ensure timestamps are strictly valid (startMs < endMs, non-negative).
     */
    private fun sanitizeAndCapTimestamps(subs: List<Subtitle>): List<Subtitle> {
        if (subs.isEmpty()) return emptyList()
        val out = ArrayList<Subtitle>(subs.size)

        var lastValidStart = 0L

        for (i in subs.indices) {
            val cur = subs[i]
            val text = cur.text.trim()
            if (text.isEmpty()) continue

            // Ensure startMs is non-negative and monotonically non-decreasing
            val cleanStart = maxOf(lastValidStart, maxOf(0L, cur.startMs))
            lastValidStart = cleanStart

            // Maximum natural duration for a single word based on character count
            val maxWordDuration = (350 + text.length * 100).toLong().coerceIn(600, 2500)

            // Cap endMs to prevent it stretching across 5+ seconds of trailing silence
            var cleanEnd = minOf(cur.endMs, cleanStart + maxWordDuration)

            // Prevent overlapping with the start of the next spoken word
            val nextSub = subs.getOrNull(i + 1)
            if (nextSub != null && nextSub.startMs > cleanStart) {
                cleanEnd = minOf(cleanEnd, nextSub.startMs)
            }

            // Guarantee a visible minimum duration of at least 150ms
            if (cleanEnd <= cleanStart) {
                cleanEnd = cleanStart + 150L
            }

            out += Subtitle(text = text, startMs = cleanStart, endMs = cleanEnd)
        }
        return out
    }

    companion object { private const val TAG = "WhisperService" }
}
