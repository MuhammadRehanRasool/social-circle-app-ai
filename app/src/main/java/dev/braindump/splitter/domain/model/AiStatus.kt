package dev.braindump.splitter.domain.model

/** Readiness of the on-device model, surfaced to the dashboard. */
sealed interface AiStatus {
    data object Unknown : AiStatus
    data object Checking : AiStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AiStatus {
        /** 0–100, or null while the total size is still unknown. */
        val percent: Int? get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else null
    }
    /** [modelName] is AICore's name for the active model (for example a nano-v3 build), when known. */
    data class Ready(val modelName: String? = null) : AiStatus
    data class Unavailable(val reason: String) : AiStatus
}
