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

    /** Keep at most [maxKeep] latest temp videos and exports to avoid storage leaks. */
    fun cleanupTempFiles(context: Context, maxKeep: Int = 2) {
        runCatching {
            listOf("videos", "output").forEach { folderName ->
                val dir = File(context.filesDir, folderName)
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    if (files != null && files.size > maxKeep) {
                        files.drop(maxKeep).forEach { oldFile ->
                            oldFile.delete()
                        }
                    }
                }
            }
        }
    }

    fun copyUriToAppStorage(context: Context, uri: Uri, fileName: String): File {
        cleanupTempFiles(context, maxKeep = 2)
        val out = File(context.filesDir, "videos").apply { mkdirs() }.resolve(fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("Cannot open $uri")
        return out
    }

    data class VideoDimensions(val widthPx: Int, val heightPx: Int)

    data class VideoOrientationInfo(
        val rawWidth: Int,
        val rawHeight: Int,
        val rotationDegrees: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val isVertical: Boolean
    )

    /**
     * Inspect full video orientation, dimensions, and rotation metadata.
     * Determines whether the video is vertical (portrait, displayHeight >= displayWidth).
     */
    fun getVideoOrientation(file: File): VideoOrientationInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
            VideoOrientationInfo(
                rawWidth = w,
                rawHeight = h,
                rotationDegrees = rot,
                displayWidth = dw,
                displayHeight = dh,
                isVertical = dh >= dw
            )
        } catch (t: Throwable) {
            VideoOrientationInfo(
                rawWidth = 1920,
                rawHeight = 1080,
                rotationDegrees = 0,
                displayWidth = 1920,
                displayHeight = 1080,
                isVertical = false
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Read display dimensions (post-rotation) from a video file. */
    fun videoDimensions(file: File): VideoDimensions {
        val info = getVideoOrientation(file)
        return VideoDimensions(info.displayWidth, info.displayHeight)
    }

    fun outputVideoFile(context: Context): File {
        val dir = File(context.filesDir, "output").apply { mkdirs() }
        return File(dir, "subtitled_${System.currentTimeMillis()}.mp4")
    }

    fun outputMkvFile(context: Context): File {
        val dir = File(context.filesDir, "output").apply { mkdirs() }
        return File(dir, "subtitled_${System.currentTimeMillis()}.mkv")
    }

    /** Save a rendered video to the user's public Movies/SubtitlesCreator/ folder (MediaStore on 29+). */
    fun saveToGallery(context: Context, source: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val isMkv = displayName.endsWith(".mkv", ignoreCase = true) || source.name.endsWith(".mkv", ignoreCase = true)
        val mimeType = if (isMkv) "video/x-matroska" else "video/mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
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

    /** Format milliseconds into standard SRT timestamp: HH:MM:SS,mmm */
    fun formatSrtTime(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val h = safeMs / 3_600_000
        val m = (safeMs % 3_600_000) / 60_000
        val s = (safeMs % 60_000) / 1000
        val millis = safeMs % 1000
        return "%02d:%02d:%02d,%03d".format(Locale.US, h, m, s, millis)
    }

    /**
     * Converts a list of Subtitles to standard .srt format string.
     * @param groupIntoLines When true, groups consecutive words into natural subtitle sentences.
     */
    fun generateSrtContent(subtitles: List<Subtitle>, groupIntoLines: Boolean = false): String {
        if (subtitles.isEmpty()) return ""
        val sb = StringBuilder()

        if (!groupIntoLines) {
            var idx = 1
            for (sub in subtitles) {
                val txt = sub.text.trim()
                if (txt.isNotEmpty()) {
                    val start = maxOf(0L, sub.startMs)
                    val end = maxOf(start + 200L, sub.endMs)
                    sb.append(idx++).append("\n")
                    sb.append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append("\n")
                    sb.append(txt).append("\n\n")
                }
            }
            return sb.toString()
        }

        var blockIndex = 1
        val currentWords = mutableListOf<String>()
        var currentStart = -1L
        var currentEnd = -1L

        fun flushBlock() {
            if (currentWords.isEmpty() || currentStart < 0) return
            val text = currentWords.joinToString(" ").trim()
            if (text.isNotEmpty()) {
                val safeStart = maxOf(0L, currentStart)
                val safeEnd = maxOf(safeStart + 500L, currentEnd)
                sb.append(blockIndex++).append("\n")
                sb.append(formatSrtTime(safeStart)).append(" --> ").append(formatSrtTime(safeEnd)).append("\n")
                sb.append(text).append("\n\n")
            }
            currentWords.clear()
            currentStart = -1L
            currentEnd = -1L
        }

        for (sub in subtitles) {
            val text = sub.text.trim()
            if (text.isEmpty()) continue

            if (currentStart == -1L) {
                currentStart = maxOf(0L, sub.startMs)
                currentEnd = maxOf(currentStart + 200L, sub.endMs)
                currentWords.add(text)
            } else {
                val gap = sub.startMs - currentEnd
                val isPunctuationEnd = currentWords.lastOrNull()?.let {
                    it.endsWith(".") || it.endsWith("?") || it.endsWith("!") || it.endsWith(";")
                } ?: false
                val totalChars = currentWords.sumOf { it.length } + text.length

                // Break subtitle into a new block if:
                // - natural pause (> 650 ms)
                // - sentence end punctuation
                // - line getting too long (> 8 words or > 40 chars)
                // - block total duration exceeds 5.5 seconds
                val blockDuration = sub.endMs - currentStart
                if (gap > 650 || isPunctuationEnd || currentWords.size >= 8 || totalChars > 42 || blockDuration > 5500) {
                    flushBlock()
                    currentStart = maxOf(0L, sub.startMs)
                    currentEnd = maxOf(currentStart + 200L, sub.endMs)
                    currentWords.add(text)
                } else {
                    currentEnd = maxOf(currentEnd, sub.endMs)
                    currentWords.add(text)
                }
            }
        }
        flushBlock()

        return sb.toString()
    }

    /** Save .srt file to Downloads/SubtitlesCreator */
    fun saveSrtToDownloads(context: Context, subtitles: List<Subtitle>, baseName: String, groupIntoLines: Boolean = false): Uri? {
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
    fun shareSrtFile(context: Context, subtitles: List<Subtitle>, baseName: String, groupIntoLines: Boolean = false) {
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

    /** Share exported video to external apps (WhatsApp, Instagram, Telegram, etc.) */
    fun shareVideoFile(context: Context, uri: Uri?, file: File?) {
        val shareUri = uri ?: file?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        } ?: return
        val isMkv = file?.name?.endsWith(".mkv", ignoreCase = true) == true
        val mimeType = if (isMkv) "video/x-matroska" else "video/mp4"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Bagikan Video Subtitle").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
