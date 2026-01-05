package horse.amazin.babymonitor.shared

data class AutoStreamConfig(
    val thresholdDb: Float,
    val durationMs: Long
)

object AutoStreamConfigData {
    const val PATH = "/auto_stream_config"
    const val KEY_THRESHOLD_DB = "threshold_db"
    const val KEY_DURATION_MS = "duration_ms"
}
