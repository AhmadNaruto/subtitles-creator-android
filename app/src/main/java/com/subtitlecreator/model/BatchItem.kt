package com.subtitlecreator.model

import java.io.File

enum class BatchStatus {
    PENDING,
    EXTRACTING,
    TRANSCRIBING,
    TRANSLATING,
    MUXING,
    COMPLETED,
    FAILED
}

data class BatchItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val file: File,
    val displayName: String,
    val status: BatchStatus = BatchStatus.PENDING,
    val progress: Int = 0,
    val stageDetail: String = "Menunggu antrean...",
    val outputFile: File? = null,
    val error: String? = null
)
