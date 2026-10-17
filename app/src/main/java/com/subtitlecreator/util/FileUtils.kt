package com.subtitlecreator.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.subtitlecreator.model.Subtitle
import java.io.File
import java.util.Locale

object FileUtils {

    fun copyUriToAppStorage(context: Context, uri: Uri, fileName: String): File {
        val out = File(context.filesDir, "videos").apply { mkdirs() }.resolve(fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("Cannot open $uri")
        return out
    }

    data class VideoDimensions(val widthPx: Int, val heightPx: Int)

    /** Read display dimensions (post-rotation) from a video file. */
    fun videoDimensions(file: File): VideoDimensions {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) VideoDimensions(h, w) else VideoDimensions(w, h)
        } catch (t: Throwable) {
            VideoDimensions(1920, 1080)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun outputVideoFile(context: Context): File {
        val dir = File(context.filesDir, "output").apply { mkdirs() }
        return File(dir, "subtitled_${System.currentTimeMillis()}.mp4")
    }

    /** Save a rendered video to the user's public Movies/SubtitlesCreator/ folder (MediaStore on 29+). */
    fun saveToGallery(context: Context, source: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SubtitlesCreator")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SubtitlesCreator").apply { mkdirs() }
            val dest = File(publicDir, displayName)
            source.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }

    /** Format milliseconds into SRT timestamp: HH:MM:SS,mmm */
    fun formatSrtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(Locale.US, h, m, s, millis)
    }

    /**
     * Converts a list of Subtitles to standard .srt format string.
     * @param groupIntoLines When true, groups consecutive words into natural subtitle sentences.
     */
    fun generateSrtContent(subtitles: List<Subtitle>, groupIntoLines: Boolean = true): String {
        if (subtitles.isEmpty()) return ""
        val sb = StringBuilder()

        if (!groupIntoLines) {
            subtitles.forEachIndexed { idx, sub ->
                val txt = sub.text.trim()
                if (txt.isNotEmpty()) {
                    sb.append(idx + 1).append("\n")
                    sb.append(formatSrtTime(sub.startMs)).append(" --> ").append(formatSrtTime(sub.endMs)).append("\n")
                    sb.append(txt).append("\n\n")
                }
            }
            return sb.toString()
        }

        var blockIndex = 1
        val currentWords = mutableListOf<String>()
        var currentStart = -1L
        var currentEnd = -1L

        for (sub in subtitles) {
            val text = sub.text.trim()
            if (text.isEmpty()) continue

            if (currentStart == -1L) {
                currentStart = sub.startMs
                currentEnd = sub.endMs
                currentWords.add(text)
            } else {
                val gap = sub.startMs - currentEnd
                val isPunctuationEnd = currentWords.lastOrNull()?.let {
                    it.endsWith(".") || it.endsWith("?") || it.endsWith("!") || it.endsWith(";")
                } ?: false

                if (gap > 650 || currentWords.size >= 8 || isPunctuationEnd) {
                    sb.append(blockIndex++).append("\n")
                    sb.append(formatSrtTime(currentStart)).append(" --> ").append(formatSrtTime(currentEnd)).append("\n")
                    sb.append(currentWords.joinToString(" ")).append("\n\n")

                    currentWords.clear()
                    currentStart = sub.startMs
                    currentEnd = sub.endMs
                    currentWords.add(text)
                } else {
                    currentEnd = sub.endMs
                    currentWords.add(text)
                }
            }
        }

        if (currentWords.isNotEmpty() && currentStart != -1L) {
            sb.append(blockIndex).append("\n")
            sb.append(formatSrtTime(currentStart)).append(" --> ").append(formatSrtTime(currentEnd)).append("\n")
            sb.append(currentWords.joinToString(" ")).append("\n\n")
        }

        return sb.toString()
    }

    /** Save .srt file to Downloads/SubtitlesCreator */
    fun saveSrtToDownloads(context: Context, subtitles: List<Subtitle>, baseName: String, groupIntoLines: Boolean = true): Uri? {
        val srtContent = generateSrtContent(subtitles, groupIntoLines)
        val fileName = "${baseName.substringBeforeLast(".")}.srt"
        val resolver = context.contentResolver

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SubtitlesCreator")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(srtContent.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SubtitlesCreator").apply { mkdirs() }
            val dest = File(publicDir, fileName)
            dest.writeText(srtContent)
            Uri.fromFile(dest)
        }
    }

    /** Share .srt file to other apps (WhatsApp, Telegram, Drive, etc.) */
    fun shareSrtFile(context: Context, subtitles: List<Subtitle>, baseName: String, groupIntoLines: Boolean = true) {
        val srtContent = generateSrtContent(subtitles, groupIntoLines)
        val fileName = "${baseName.substringBeforeLast(".")}.srt"
        val cacheFile = File(context.cacheDir, fileName)
        cacheFile.writeText(srtContent)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan Subtitle (.srt)"))
    }
}
