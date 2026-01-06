package horse.amazin.babymonitor.shared

data class AutoStreamConfig(
    val thresholdDb: Float,
    val durationMs: Int
)

object AutoStreamConfigData {
    const val PATH = "/auto_stream_config"
    const val KEY_THRESHOLD_DB = "threshold_db"
    const val KEY_MIN_DURATION_MS = "min_duration_ms"
    const val KEY_ENABLED = "enabled"
}
