package horse.amazin.babymonitor.shared

object LoudnessData {
    const val PATH = "/loudness"
    const val KEY_DB = "db"
    const val KEY_TIMESTAMP = "timestamp"
}

object AudioStreamChannel {
    const val PATH = "/audio"
}

object AudioCodecConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val FRAME_DURATION_MS = 20
    const val FRAME_SIZE_SAMPLES = SAMPLE_RATE / 1000 * FRAME_DURATION_MS
    const val BITRATE_BPS = 16000
    const val MAX_PACKET_SIZE = 1276
}
