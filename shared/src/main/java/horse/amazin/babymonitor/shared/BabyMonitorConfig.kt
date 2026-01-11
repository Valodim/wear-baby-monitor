package horse.amazin.babymonitor.shared

object BabyMonitorSettings {
    const val KEY_ACTION = "action"
    const val KEY_THRESHOLD_DB = "threshold_db"
    const val KEY_MIN_DURATION_MS = "min_duration_ms"

    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
}

const val CAPABILITY_BABY_MONITOR_SENDER = "baby_monitor_sender"

const val MESSAGE_PATH_RECEIVER_SET_ENABLED = "/setEnabled"
const val MESSAGE_PATH_SENDER_LOUDNESS = "/loudness"
const val CHANNEL_PATH_SENDER_AUDIO = "/audio"