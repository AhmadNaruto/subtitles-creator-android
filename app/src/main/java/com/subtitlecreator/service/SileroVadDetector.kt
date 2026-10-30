package com.subtitlecreator.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Speech segment detected by Silero VAD.
 *
 * @param startSample starting sample index in the PCM float array (inclusive)
 * @param endSample ending sample index in the PCM float array (exclusive)
 * @param startMs start timestamp in milliseconds
 * @param endMs end timestamp in milliseconds
 */
data class SpeechSegment(
    val startSample: Int,
    val endSample: Int,
    val startMs: Long,
    val endMs: Long
)

/**
 * High-performance Voice Activity Detection (VAD) using Silero VAD v5 ONNX model.
 * Detects human speech segments in 16kHz mono audio, allowing transcription pipelines
 * to skip silent gaps and non-speech audio.
 */
class SileroVadDetector(private val context: Context) : AutoCloseable {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    companion object {
        private const val TAG = "SileroVadDetector"
        private const val MODEL_PATH = "models/silero_vad.onnx"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // 32 ms @ 16kHz
    }

    @Synchronized
    private fun getSession(): Pair<OrtEnvironment, OrtSession> {
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val session = ortSession ?: run {
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            env.createSession(modelBytes, sessionOptions).also { ortSession = it }
        }
        return Pair(env, session)
    }

    /**
     * Run voice activity detection on a 16kHz mono float32 PCM array.
     * Returns a list of [SpeechSegment] with speech padding and minimum duration filtering.
     *
     * @param pcm normalized float32 PCM samples [-1.0, 1.0] at 16000 Hz
     * @param threshold speech probability threshold (default 0.5)
     * @param negThreshold silence probability threshold (default 0.35)
     * @param minSpeechMs minimum speech duration to be considered valid speech (default 250ms)
     * @param minSilenceMs minimum silence duration to split segments (default 400ms)
     * @param speechPadMs padding added before and after speech in ms (default 150ms)
     */
    fun detectSpeechSegments(
        pcm: FloatArray,
        threshold: Float = 0.5f,
        negThreshold: Float = 0.35f,
        minSpeechMs: Int = 250,
        minSilenceMs: Int = 400,
        speechPadMs: Int = 150,
        onProgress: ((Int) -> Unit)? = null
    ): List<SpeechSegment> {
        if (pcm.isEmpty()) return emptyList()

        val (env, session) = try {
            getSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD session: ${e.message}", e)
            return emptyList()
        }

        val minSpeechSamples = (minSpeechMs * SAMPLE_RATE) / 1000
        val minSilenceSamples = (minSilenceMs * SAMPLE_RATE) / 1000
        val speechPadSamples = (speechPadMs * SAMPLE_RATE) / 1000

        // Check if model expects scalar sr or 1D tensor [1]
        val srInputInfo = session.inputInfo["sr"]
        val isSrScalar = srInputInfo?.let {
            val shape = (it.info as? ai.onnxruntime.TensorInfo)?.shape
            shape != null && shape.isEmpty()
        } ?: true

        val frameBuffer = FloatBuffer.allocate(FRAME_SIZE)
        val frameShape = longArrayOf(1, FRAME_SIZE.toLong())

        // Silero VAD hidden state: shape [2, 1, 128]
        val state = FloatArray(2 * 1 * 128)
        val stateShape = longArrayOf(2, 1, 128)

        val totalSamples = pcm.size
        val totalFrames = totalSamples / FRAME_SIZE
        if (totalFrames == 0) return emptyList()

        val rawSegments = ArrayList<SpeechSegment>()

        var triggered = false
        var tempStartSample = 0
        var tempEndSample = 0

        var currentSample = 0
        var frameIndex = 0

        while (currentSample + FRAME_SIZE <= totalSamples) {
            frameBuffer.clear()
            frameBuffer.put(pcm, currentSample, FRAME_SIZE)
            frameBuffer.flip()

            val stateBuffer = FloatBuffer.wrap(state)

            val inputTensor = OnnxTensor.createTensor(env, frameBuffer, frameShape)
            val stateTensor = OnnxTensor.createTensor(env, stateBuffer, stateShape)
            val srTensor = if (isSrScalar) {
                OnnxTensor.createTensor(env, SAMPLE_RATE.toLong())
            } else {
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf(1))
            }

            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )

            var speechProb = 0.0f
            try {
                session.run(inputs).use { results ->
                    val outTensor = results.get(0) as? OnnxTensor
                    speechProb = when (val v = outTensor?.value) {
                        is Array<*> -> {
                            val row = v[0]
                            if (row is FloatArray) row[0] else 0.0f
                        }
                        is FloatArray -> v[0]
                        is Float -> v
                        else -> 0.0f
                    }

                    // Update state tensor for next chunk (output 1 is stateN)
                    if (results.size() > 1) {
                        val nextStateTensor = results.get(1) as? OnnxTensor
                        nextStateTensor?.floatBuffer?.let { nb ->
                            nb.rewind()
                            nb.get(state)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Inference error at sample $currentSample: ${e.message}")
            } finally {
                inputTensor.close()
                stateTensor.close()
                srTensor.close()
            }

            // State Machine for voice detection
            if (speechProb >= threshold && tempEndSample != 0) {
                tempEndSample = 0
            }

            if (speechProb >= threshold && !triggered) {
                triggered = true
                tempStartSample = currentSample
            }

            if (speechProb < negThreshold && triggered) {
                if (tempEndSample == 0) {
                    tempEndSample = currentSample
                }
                if (currentSample - tempEndSample >= minSilenceSamples) {
                    if (tempEndSample - tempStartSample >= minSpeechSamples) {
                        val start = maxOf(0, tempStartSample - speechPadSamples)
                        val end = minOf(totalSamples, tempEndSample + speechPadSamples)
                        val startMs = (start.toLong() * 1000L) / SAMPLE_RATE
                        val endMs = (end.toLong() * 1000L) / SAMPLE_RATE
                        rawSegments.add(SpeechSegment(start, end, startMs, endMs))
                    }
                    triggered = false
                    tempEndSample = 0
                    tempStartSample = 0
                }
            }

            currentSample += FRAME_SIZE
            frameIndex++
            if (frameIndex % 150 == 0) {
                val pct = ((currentSample.toFloat() / totalSamples.toFloat()) * 100).toInt()
                onProgress?.invoke(pct.coerceIn(0, 100))
            }
        }

        // Tail segment if speech was active at the end of the audio
        if (triggered && (totalSamples - tempStartSample >= minSpeechSamples)) {
            val start = maxOf(0, tempStartSample - speechPadSamples)
            val end = totalSamples
            val startMs = (start.toLong() * 1000L) / SAMPLE_RATE
            val endMs = (end.toLong() * 1000L) / SAMPLE_RATE
            rawSegments.add(SpeechSegment(start, end, startMs, endMs))
        }

        onProgress?.invoke(100)

        val merged = mergeSegments(rawSegments, minSilenceSamples)
        Log.i(TAG, "VAD detected ${rawSegments.size} raw segments -> merged to ${merged.size} segments")
        return merged
    }

    /**
     * Merge adjacent segments that are closer than minSilenceSamples or overlapping.
     */
    private fun mergeSegments(
        segments: List<SpeechSegment>,
        minSilenceSamples: Int
    ): List<SpeechSegment> {
        if (segments.isEmpty()) return emptyList()

        val merged = ArrayList<SpeechSegment>()
        var curStart = segments[0].startSample
        var curEnd = segments[0].endSample

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.startSample <= curEnd + minSilenceSamples) {
                curEnd = maxOf(curEnd, next.endSample)
            } else {
                val startMs = (curStart.toLong() * 1000L) / SAMPLE_RATE
                val endMs = (curEnd.toLong() * 1000L) / SAMPLE_RATE
                merged.add(SpeechSegment(curStart, curEnd, startMs, endMs))
                curStart = next.startSample
                curEnd = next.endSample
            }
        }

        val lastStartMs = (curStart.toLong() * 1000L) / SAMPLE_RATE
        val lastEndMs = (curEnd.toLong() * 1000L) / SAMPLE_RATE
        merged.add(SpeechSegment(curStart, curEnd, lastStartMs, lastEndMs))

        return merged
    }

    override fun close() {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        ortSession = null
        try {
            ortEnv?.close()
        } catch (_: Exception) {}
        ortEnv = null
    }
}
