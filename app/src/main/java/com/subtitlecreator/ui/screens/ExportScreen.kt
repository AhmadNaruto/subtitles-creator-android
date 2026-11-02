package com.subtitlecreator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.subtitlecreator.ui.components.KeepScreenOn
import com.subtitlecreator.util.FileUtils

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ExportScreen(
    state: com.subtitlecreator.ui.UiState,
    onDone: () -> Unit,
    onCancel: () -> Unit = {},
    onBackToEditor: () -> Unit = {}
) {
    KeepScreenOn()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val exportUri = remember(state.savedUri, state.exportedFile) {
        state.savedUri ?: state.exportedFile?.let { android.net.Uri.fromFile(it) }
    }

    val player = remember(exportUri) {
        exportUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            player?.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.error != null) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Export Gagal",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onBackToEditor,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Kembali ke Editor")
            }
        } else if (state.exporting) {
            val isMkv = state.exportType.equals("mkv", ignoreCase = true)
            Text(
                "${state.exportProgress}%",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (isMkv) "Menggabungkan Subtitle ke MKV" else "Membakar Subtitle ke Video MP4",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.exportDetail.ifEmpty {
                    if (isMkv) "Menggabungkan softsub ke kontainer MKV via stream copy..."
                    else "Merender video dengan subtitle..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { (state.exportProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Batalkan Export")
            }
        } else if (state.exportedFile != null) {
            val isMkv = state.exportedFile.name.endsWith(".mkv", ignoreCase = true)
            val isVertical = state.videoHeightPx >= state.videoWidthPx && state.videoHeightPx > 0
            val playerHeight = if (isVertical) 340.dp else 220.dp

            // Video Player Box: Preview video hasil burn / softsub langsung
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(playerHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (player != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info Hasil & Orientasi
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (isMkv) "File MKV Selesai!" else "Video Selesai!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            // Orientasi badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (isVertical) "📱 Orientasi Vertikal (Portrait)" else "🖥️ Orientasi Horizontal (Landscape)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                if (state.savedUri != null) "Tersimpan di Movies/SubtitlesCreator"
                else "Tersimpan: ${state.exportedFile.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // Tombol Bagikan Video Hasil Langsung
            Button(
                onClick = {
                    FileUtils.shareVideoFile(context, state.savedUri, state.exportedFile)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Bagikan Video Hasil")
            }

            Spacer(Modifier.height(12.dp))

            // Opsi Simpan & Bagikan SRT dari layar selesai
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val baseName = state.videoFile?.name ?: "subtitle_${System.currentTimeMillis()}.mp4"
                        val uri = FileUtils.saveSrtToDownloads(context, state.subtitles, baseName)
                        if (uri != null) {
                            Toast.makeText(context, "File .srt disimpan di folder Downloads!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Gagal menyimpan file .srt", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Unduh .SRT")
                }

                OutlinedButton(
                    onClick = {
                        val baseName = state.videoFile?.name ?: "subtitle_${System.currentTimeMillis()}.mp4"
                        FileUtils.shareSrtFile(context, state.subtitles, baseName)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Bagikan .SRT")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Navigasi Kembali ke Editor atau Video Baru
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBackToEditor,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit Lagi")
                }

                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Video Baru")
                }
            }
        }
    }
}
