package com.subtitlecreator.ui.screens

import android.graphics.Typeface as AndroidTypeface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.subtitlecreator.model.Subtitle
import com.subtitlecreator.service.PipelineStore
import com.subtitlecreator.service.TranslationService
import com.subtitlecreator.ui.AppViewModel
import com.subtitlecreator.util.FileUtils
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun EditorScreen(
    state: com.subtitlecreator.ui.UiState,
    vm: AppViewModel,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    var showTranslateDialog by remember { mutableStateOf(false) }

    val player = remember(state.videoFile) {
        ExoPlayer.Builder(context).build().apply {
            state.videoFile?.let {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it)))
                prepare()
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(30)
        }
    }
    val activeIndex = remember(state.subtitles, positionMs) {
        state.subtitles.indexOfFirst { positionMs in it.startMs..it.endMs }
    }
    val activeText = state.subtitles.getOrNull(activeIndex)?.text ?: ""

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    val montserratFamily = remember {
        val tf = runCatching { AndroidTypeface.createFromAsset(context.assets, "fonts/Montserrat-Bold.ttf") }
            .getOrElse { AndroidTypeface.DEFAULT_BOLD }
        FontFamily(androidx.compose.ui.text.font.Typeface(tf))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Video Preview Player Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic Subtitle Overlay
            if (activeText.isNotEmpty()) {
                val density = LocalDensity.current
                val fontSizeSp = (state.subtitleFontSize * 0.35f).coerceIn(14f, 56f).sp
                val shadowRadiusPx = with(density) { (fontSizeSp * 0.10f).toPx() }
                val shadowDxPx = with(density) { (fontSizeSp * 0.035f).toPx() }
                val shadowDyPx = with(density) { (fontSizeSp * 0.065f).toPx() }
                val shadowBlurPx = shadowRadiusPx.coerceAtLeast(1f)

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val boxHeightPx = with(density) { maxHeight.toPx() }
                    val yOffset = with(density) {
                        (boxHeightPx * state.subtitlePositionFraction).toDp()
                    }

                    Text(
                        text = activeText,
                        color = Color(0xFF, 0xD4, 0x00), // yellow
                        fontFamily = montserratFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSizeSp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color(0, 0, 0, 128),
                                offset = Offset(shadowDxPx, shadowDyPx),
                                blurRadius = shadowBlurPx
                            ),
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = yOffset)
                            .padding(horizontal = 8.dp)
                            .pointerInput(boxHeightPx) {
                                detectVerticalDragGestures { change, dy ->
                                    change.consume()
                                    val cur = PipelineStore.current().subtitlePositionFraction
                                    vm.setSubtitlePosition(cur + dy / boxHeightPx)
                                }
                            }
                    )
                }
            }
        }

        // Action Toolbar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${state.subtitles.size} kata",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            // Tombol Terjemahkan
            OutlinedButton(
                onClick = { showTranslateDialog = true },
                enabled = state.subtitles.isNotEmpty() && !state.isTranslating,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Translate", style = MaterialTheme.typography.labelMedium)
            }

            // Tombol Simpan .SRT Langsung
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
                enabled = state.subtitles.isNotEmpty() && !state.isTranslating,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text(".SRT", style = MaterialTheme.typography.labelMedium)
            }

            // Tombol Bagikan .SRT
            IconButton(
                onClick = {
                    val baseName = state.videoFile?.name ?: "subtitle_${System.currentTimeMillis()}.mp4"
                    FileUtils.shareSrtFile(context, state.subtitles, baseName)
                },
                enabled = state.subtitles.isNotEmpty() && !state.isTranslating,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Bagikan .SRT", modifier = Modifier.size(18.dp))
            }

            // Tombol Burn Video (MP4)
            Button(
                onClick = onExport,
                enabled = state.subtitles.isNotEmpty() && !state.isTranslating,
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text("Burn MP4", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Font size slider
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                Slider(
                    value = state.subtitleFontSize.toFloat(),
                    onValueChange = { vm.setFontSize(it.toInt()) },
                    valueRange = 32f..180f,
                    modifier = Modifier.weight(1f)
                )
                Text("${state.subtitleFontSize}", modifier = Modifier.width(36.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
            }
        }

        // List editor kata per kata
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(state.subtitles) { idx, sub ->
                SubtitleRow(
                    sub = sub,
                    isActive = idx == activeIndex,
                    onClick = { player.seekTo(sub.startMs) },
                    onTextChange = { vm.updateSubtitle(idx, text = it) },
                    onDelete = { vm.deleteSubtitle(idx) },
                    onAdd = { vm.addSubtitle(idx) }
                )
            }
        }
    }

    // Modal Pilihan Bahasa Terjemahan
    if (showTranslateDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateDialog = false },
            title = {
                Text("Terjemahkan Subtitle", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Pilih bahasa tujuan (menggunakan Google Translate Web):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TranslationService.SUPPORTED_LANGUAGES.forEach { lang ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTranslateDialog = false
                                    vm.translateSubtitles(
                                        targetLang = lang.code,
                                        onDone = {
                                            Toast.makeText(context, "Berhasil diterjemahkan ke ${lang.name}!", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(lang.flag, fontSize = 20.sp)
                                Text(lang.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTranslateDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Loading saat Proses Translasi Berjalan
    if (state.isTranslating) {
        AlertDialog(
            onDismissRequest = { /* non-cancelable */ },
            title = {
                Text("Menerjemahkan Subtitle...", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Menerjemahkan kata ${state.translationProgress} dari ${state.translationTotal}...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (state.translationTotal > 0)
                                (state.translationProgress.toFloat() / state.translationTotal.toFloat()).coerceIn(0f, 1f)
                            else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SubtitleRow(
    sub: Subtitle,
    isActive: Boolean,
    onClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
    onAdd: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(0.18f).padding(end = 8.dp)) {
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        formatMs(sub.startMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    formatMs(sub.endMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = sub.text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add after") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

private fun formatMs(ms: Long): String {
    val m = ms / 60_000
    val s = (ms % 60_000) / 1000
    val cs = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}
