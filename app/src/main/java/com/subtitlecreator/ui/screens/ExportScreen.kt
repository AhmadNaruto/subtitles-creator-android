package com.subtitlecreator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitlecreator.ui.components.KeepScreenOn
import com.subtitlecreator.util.FileUtils

@Composable
fun ExportScreen(
    state: com.subtitlecreator.ui.UiState,
    onDone: () -> Unit
) {
    KeepScreenOn()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.exporting) {
            Text(
                "${state.exportProgress}%",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("Burning subtitles into video", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(
                progress = { (state.exportProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
        } else if (state.exportedFile != null) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Selesai!", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.savedUri != null) "Video tersimpan di Movies/SubtitlesCreator"
                else "Video tersimpan: ${state.exportedFile.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            // Opsi Simpan & Bagikan SRT dari layar selesai
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Unduh .SRT")
                }

                OutlinedButton(
                    onClick = {
                        val baseName = state.videoFile?.name ?: "subtitle_${System.currentTimeMillis()}.mp4"
                        FileUtils.shareSrtFile(context, state.subtitles, baseName)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bagikan .SRT")
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Proses Video Lain", style = MaterialTheme.typography.titleMedium) }
        }

        state.error?.let { err ->
            Spacer(Modifier.height(24.dp))
            Text(err, color = MaterialTheme.colorScheme.error)
        }
    }
}
