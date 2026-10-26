package com.subtitlecreator.service

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpeg wrapper: extract 16kHz mono PCM16 WAV (for Whisper).
 */
class FFmpegService(private val context: Context) {

    suspend fun extractAudio16kMono(videoIn: File, wavOut: File): File = withContext(Dispatchers.IO) {
        if (wavOut.exists()) wavOut.delete()
        // Use aresample=async=1000:first_pts=0 to ensure the audio timeline precisely
        // aligns with video presentation timestamps (PTS=0) and prevents drift over long videos (10+ min).
        val cmd = "-y -i \"${videoIn.absolutePath}\" -vn -af \"aresample=async=1000:first_pts=0\" -ac 1 -ar 16000 -c:a pcm_s16le \"${wavOut.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString.orEmpty()
            if (logs.contains("does not contain any stream") || logs.contains("Output file is empty") || logs.contains("matches no streams")) {
                error("Video tidak memiliki stream audio atau format audio tidak didukung.")
            }
            error("FFmpeg extract failed rc=${session.returnCode}\n$logs")
        }
        Log.i(TAG, "Extracted ${wavOut.length() / 1024} KB of PCM")
        wavOut
    }

    companion object {
        private const val TAG = "FFmpegService"
    }
}
