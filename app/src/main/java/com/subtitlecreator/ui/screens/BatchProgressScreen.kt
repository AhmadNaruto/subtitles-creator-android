package com.subtitlecreator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitlecreator.model.BatchItem
import com.subtitlecreator.model.BatchStatus
import com.subtitlecreator.ui.AppViewModel
import com.subtitlecreator.ui.UiState
import com.subtitlecreator.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchProgressScreen(
    state: UiState,
    vm: AppViewModel,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val totalCount = state.batchItems.size
    val completedCount = state.batchItems.count { it.status == BatchStatus.COMPLETED }
    val failedCount = state.batchItems.count { it.status == BatchStatus.FAILED }
    val isRunning = state.batchRunning

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Proses Antrean Batch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (isRunning) "Sedang memproses antrean..." else "Antrean selesai",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackToHome,
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = { vm.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Batalkan Antrean Batch")
                        }
                    } else {
                        Button(
                            onClick = onBackToHome,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Selesai & Kembali ke Beranda")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Overall Summary Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Status: $completedCount dari $totalCount video selesai",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (failedCount > 0) {
                            Text(
                                text = "$failedCount gagal",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val progressFraction = if (totalCount > 0) (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Daftar Video Antrean",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(state.batchItems) { index, item ->
                    BatchItemCard(
                        item = item,
                        index = index,
                        isCurrent = isRunning && state.batchCurrentIndex == index,
                        onShare = {
                            item.outputFile?.let { file ->
                                FileUtils.shareVideoFile(context, null, file)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun BatchItemCard(
    item: BatchItem,
    index: Int,
    isCurrent: Boolean,
    onShare: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusBadge(status = item.status)
            }

            Text(
                text = item.stageDetail,
                style = MaterialTheme.typography.bodySmall,
                color = when (item.status) {
                    BatchStatus.FAILED -> MaterialTheme.colorScheme.error
                    BatchStatus.COMPLETED -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (item.status in listOf(BatchStatus.EXTRACTING, BatchStatus.TRANSCRIBING, BatchStatus.TRANSLATING, BatchStatus.MUXING)) {
                if (item.progress > 0) {
                    LinearProgressIndicator(
                        progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (item.status == BatchStatus.COMPLETED && item.outputFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bagikan MKV")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: BatchStatus) {
    val (label, bg, fg) = when (status) {
        BatchStatus.PENDING -> Triple("Antre", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        BatchStatus.EXTRACTING -> Triple("Audio", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        BatchStatus.TRANSCRIBING -> Triple("AI Whisper", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        BatchStatus.TRANSLATING -> Triple("Terjemah", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        BatchStatus.MUXING -> Triple("Muxing", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        BatchStatus.COMPLETED -> Triple("Selesai", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        BatchStatus.FAILED -> Triple("Gagal", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
