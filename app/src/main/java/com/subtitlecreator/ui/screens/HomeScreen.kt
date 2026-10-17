package com.subtitlecreator.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtitlecreator.service.ModelDownloadService
import com.subtitlecreator.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: com.subtitlecreator.ui.UiState,
    vm: AppViewModel,
    onStartTranscribe: () -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(vm::onVideoPicked)
    }

    var showModelDialog by remember { mutableStateOf(false) }
    val currentSpec = remember(state.selectedModelId) {
        vm.modelDl.getModelById(state.selectedModelId)
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Subtitles Creator",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "On-device AI Subtitle Generator",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. Model Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.modelReady) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Model Ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.secondary)
                            Text("Model Needs Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    TextButton(onClick = { showModelDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pilih Model")
                    }
                }

                // Info Model Aktif
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = currentSpec.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (currentSpec.id) {
                            "tiny-q5_1" -> "⚡ Sangat Cepat & Ringan — Ramah Semua HP Android (~31 MB)"
                            "base-q5_1" -> "⭐ Rekomendasi Utama — Keseimbangan Kecepatan & Akurasi (~57 MB)"
                            "small-q5_1" -> "🎯 Akurasi Tinggi — Ideal untuk Kosakata Beragam (~182 MB)"
                            "medium-q5_0" -> "🔍 Presisi Ekstra — Untuk Audio Menantang (~515 MB)"
                            else -> "🚀 Flagship Turbo — Untuk Chipset Kelas Atas (~574 MB)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!state.modelReady) {
                    if (state.modelTotalMb > 0) {
                        LinearProgressIndicator(
                            progress = { (state.modelDownloadMb.toFloat() / state.modelTotalMb.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Mengunduh: ${state.modelDownloadMb} / ${state.modelTotalMb} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = { vm.downloadModel() },
                        enabled = state.modelTotalMb == 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Model (${currentSpec.sizeMb} MB)")
                    }
                }
            }
        }

        // 2. Video Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.VideoLibrary, null)
                    Text("Pilih Video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    state.videoFile?.name ?: "Belum ada video yang dipilih",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        videoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.VideoOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.videoFile == null) "Pilih Video dari Galeri" else "Ganti Video")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onStartTranscribe,
            enabled = state.modelReady && state.videoFile != null && !state.transcribing,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Mulai Transkripsi Video", style = MaterialTheme.typography.titleMedium)
        }

        state.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    // Modal Dialog Pilihan Model
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = {
                Text("Pilih Model Whisper", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Pilih model yang sesuai dengan spesifikasi perangkat Anda:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ModelDownloadService.ALL_MODELS.forEach { spec ->
                        val isSelected = state.selectedModelId == spec.id
                        val isDownloaded = state.downloadedModelIds.contains(spec.id) || vm.modelDl.isDownloaded(spec)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectModel(spec.id)
                                    showModelDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = spec.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isDownloaded) {
                                        Text(
                                            text = "Tersimpan",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Text(
                                    text = when (spec.id) {
                                        "tiny-q5_1" -> "⚡ Paling ringan, hemat baterai, cocok untuk semua HP."
                                        "base-q5_1" -> "⭐ Rekomendasi: pas antara kecepatan dan akurasi."
                                        "small-q5_1" -> "🎯 Akurasi tinggi, bagus untuk ucapan cepat."
                                        "medium-q5_0" -> "🔍 Presisi tinggi, membutuhkan RAM lebih besar."
                                        else -> "🚀 Akurasi maksimal, direkomendasikan untuk Snapdragon 8 Gen."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }
}
