package com.subtitlecreator.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitlecreator.service.TranslationService
import com.subtitlecreator.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: com.subtitlecreator.ui.UiState,
    vm: AppViewModel,
    onStartTranscribe: () -> Unit,
    onStartBatch: () -> Unit = {}
) {
    val context = LocalContext.current
    var showModelDialog by remember { mutableStateOf(false) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    var customUrlInput by remember { mutableStateOf("") }
    var customNameInput by remember { mutableStateOf("") }
    var selectedModeTab by remember { mutableStateOf(if (state.batchRunning || state.batchItems.isNotEmpty()) 1 else 0) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(vm::onVideoPicked)
    }

    val multiVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        vm.onMultipleVideosPicked(uris)
    }

    val modelFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            Toast.makeText(context, "Sedang mengimpor model...", Toast.LENGTH_SHORT).show()
            vm.importModelFromStorage(
                uri = it,
                onDone = { spec ->
                    Toast.makeText(context, "Model '${spec.displayName}' siap digunakan!", Toast.LENGTH_LONG).show()
                    showModelDialog = false
                },
                onError = { err ->
                    Toast.makeText(context, "Gagal impor: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }


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
                            Text("Model Belum Diunduh", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            "large-v3-turbo-q5" -> "🚀 Flagship Turbo — Untuk Chipset Kelas Atas (~574 MB)"
                            else -> "🔗 Model Custom: ${currentSpec.url.take(50)}..."
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
                        val sizeText = if (currentSpec.sizeMb > 0) " (${currentSpec.sizeMb} MB)" else ""
                        Text("Download Model$sizeText")
                    }
                }
            }
        }

        // Banner jika Batch sedang berjalan
        if (state.batchRunning) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartBatch() }
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⚡ Antrean Batch Sedang Berjalan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Memproses video ${state.batchCurrentIndex + 1} dari ${state.batchItems.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = onStartBatch) {
                        Text("Lihat")
                    }
                }
            }
        }

        // Mode Switcher (Single Video vs Batch Antrean)
        TabRow(
            selectedTabIndex = selectedModeTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Tab(
                selected = selectedModeTab == 0,
                onClick = { selectedModeTab = 0 },
                text = { Text("Video Tunggal") },
                icon = { Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
            Tab(
                selected = selectedModeTab == 1,
                onClick = { selectedModeTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Batch Antrean")
                        if (state.batchItems.isNotEmpty()) {
                            Badge { Text("${state.batchItems.size}") }
                        }
                    }
                },
                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        if (selectedModeTab == 0) {
            // MODE VIDEO TUNGGAL

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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.videoFile?.name ?: "Belum ada video yang dipilih",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.videoFile != null && state.videoWidthPx > 0 && state.videoHeightPx > 0) {
                            val isVertical = state.videoHeightPx >= state.videoWidthPx
                            Text(
                                text = "${state.videoWidthPx} × ${state.videoHeightPx} • ${if (isVertical) "📱 Orientasi Vertikal (Portrait)" else "🖥️ Orientasi Horizontal (Landscape)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
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

            // 3. Language Selector Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Bahasa Audio Sumber",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Memilih bahasa secara manual membuat transkripsi lebih cepat (~20%) dan mencegah halusinasi deteksi bahasa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val languages = listOf(
                        "auto" to "🌐 Otomatis",
                        "id" to "🇮🇩 Indonesia",
                        "en" to "🇬🇧 English",
                        "ja" to "🇯🇵 日本語",
                        "ar" to "🇸🇦 العربية",
                        "ko" to "🇰🇷 한국어",
                        "zh" to "🇨🇳 中文",
                        "ms" to "🇲🇾 Melayu",
                        "es" to "🇪🇸 Español",
                        "fr" to "🇫🇷 Français",
                        "de" to "🇩🇪 Deutsch",
                        "ru" to "🇷🇺 Русский"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.forEach { (code, label) ->
                            FilterChip(
                                selected = state.sourceLanguage == code,
                                onClick = { vm.setSourceLanguage(code) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onStartTranscribe,
                enabled = state.modelReady && state.videoFile != null && !state.transcribing,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Mulai Transkripsi Video", style = MaterialTheme.typography.titleMedium)
            }

        } else {
            // MODE BATCH ANTREAN

            // 1. Batch Video Selector Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.VideoLibrary, null)
                            Text(
                                "Antrean Video (${state.batchItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (state.batchItems.isNotEmpty() && !state.batchRunning) {
                            TextButton(onClick = { vm.clearBatch() }) {
                                Text("Kosongkan", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    if (state.batchItems.isEmpty()) {
                        Text(
                            text = "Belum ada video di antrean. Anda dapat memilih beberapa video sekaligus dari galeri untuk diproses secara otomatis berurutan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            state.batchItems.forEachIndexed { idx, item ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${idx + 1}. ${item.displayName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (!state.batchRunning) {
                                            IconButton(
                                                onClick = { vm.removeBatchItem(item.id) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Hapus",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            multiVideoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        },
                        enabled = !state.batchRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Tambah Video (Pilih Banyak dari Galeri)")
                    }
                }
            }

            // 2. Audio Source Language Selector
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Bahasa Audio Sumber",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Pilih bahasa percakapan dalam video agar deteksi Whisper lebih akurat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val languages = listOf(
                        "auto" to "🌐 Otomatis",
                        "id" to "🇮🇩 Indonesia",
                        "en" to "🇬🇧 English",
                        "ja" to "🇯🇵 日本語",
                        "ar" to "🇸🇦 العربية",
                        "ko" to "🇰🇷 한국어",
                        "zh" to "🇨🇳 中文",
                        "ms" to "🇲🇾 Melayu",
                        "es" to "🇪🇸 Español",
                        "fr" to "🇫🇷 Français",
                        "de" to "🇩🇪 Deutsch",
                        "ru" to "🇷🇺 Русский"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.forEach { (code, label) ->
                            FilterChip(
                                selected = state.sourceLanguage == code,
                                onClick = { vm.setSourceLanguage(code) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // 3. Batch Target Translation Language Selector
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Terjemahan Otomatis (Opsional)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Otomatis menerjemahkan subtitle ke bahasa target untuk setiap video dalam antrean sebelum disimpan ke galeri.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.batchTargetLanguage == "none",
                            onClick = { vm.setBatchTargetLanguage("none") },
                            label = { Text("🚫 Tanpa Terjemahan (Asli)") }
                        )

                        TranslationService.SUPPORTED_LANGUAGES.forEach { lang ->
                            FilterChip(
                                selected = state.batchTargetLanguage == lang.code,
                                onClick = { vm.setBatchTargetLanguage(lang.code) },
                                label = { Text("${lang.flag} ${lang.name}") }
                            )
                        }
                    }
                }
            }

            // 4. Batch Info Note
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "⚡ Format Output MKV Super Cepat",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Setiap video batch akan dimuxing ke format MKV menggunakan FFmpeg Stream-Copy (hanya 2-5 detik per video) dengan soft subtitle, menjaga kualitas video asli 100% dan langsung tersimpan di folder Movies/SubtitlesCreator di Galeri.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    vm.startBatchProcess()
                    onStartBatch()
                },
                enabled = state.modelReady && state.batchItems.isNotEmpty() && !state.batchRunning,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = if (state.batchItems.isEmpty()) "Tambahkan Video ke Antrean" else "Mulai Proses Batch (${state.batchItems.size} Video)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
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
                    // Tombol Impor File Model dari File HP (.bin)
                    OutlinedButton(
                        onClick = {
                            modelFilePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("📁 Impor Model dari File HP (.bin)")
                    }

                    // Tombol Tambah Model Manual dari URL / Hugging Face
                    OutlinedButton(
                        onClick = {
                            showModelDialog = false
                            showAddCustomDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("+ Tambah Model dari Hugging Face / URL")
                    }

                    Text(
                        "Model yang tersedia:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    vm.modelDl.getAllModels().forEach { spec ->
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
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isDownloaded) {
                                        Text(
                                            text = "Tersimpan",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )

                                        IconButton(
                                            onClick = {
                                                vm.deleteDownloadedModel(spec.id)
                                                Toast.makeText(context, "File model '${spec.displayName}' telah dihapus dari HP", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Hapus file model",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else if (spec.isCustom) {
                                        IconButton(
                                            onClick = {
                                                vm.removeCustomModel(spec.id)
                                                Toast.makeText(context, "Model custom dihapus", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Hapus",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = when (spec.id) {
                                        "tiny-q5_1" -> "⚡ Paling ringan, hemat baterai, cocok untuk semua HP."
                                        "base-q5_1" -> "⭐ Rekomendasi: pas antara kecepatan dan akurasi."
                                        "small-q5_1" -> "🎯 Akurasi tinggi, bagus untuk ucapan cepat."
                                        "medium-q5_0" -> "🔍 Presisi tinggi, membutuhkan RAM lebih besar."
                                        "large-v3-turbo-q5" -> "🚀 Akurasi maksimal, direkomendasikan untuk Snapdragon 8 Gen."
                                        else -> "🔗 Custom Model (Link manual)"
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

    // Modal Input Custom Model URL
    if (showAddCustomDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomDialog = false },
            title = {
                Text("Tambah Model Whisper", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Masukkan link download langsung file GGML Whisper (.bin), misalnya dari Hugging Face repository.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = customUrlInput,
                        onValueChange = { customUrlInput = it },
                        label = { Text("Link URL Model (.bin)") },
                        placeholder = { Text("https://huggingface.co/.../ggml-model.bin") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customNameInput,
                        onValueChange = { customNameInput = it },
                        label = { Text("Nama Model (Opsional)") },
                        placeholder = { Text("Contoh: Whisper Indo FineTuned") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "💡 Tips: Link Hugging Face berformat '/blob/' akan otomatis diubah menjadi direct download '/resolve/'. Pastikan file berformat GGML Whisper (.bin).",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = customUrlInput.trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            vm.addCustomModel(url, customNameInput.ifEmpty { null })
                            showAddCustomDialog = false
                            customUrlInput = ""
                            customNameInput = ""
                            Toast.makeText(context, "Model kustom berhasil ditambahkan!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "URL harus diawali dengan http:// atau https://", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = customUrlInput.isNotBlank()
                ) {
                    Text("Simpan & Pilih")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
