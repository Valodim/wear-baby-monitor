package horse.amazin.babymonitor.wear

import android.os.Parcel
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import horse.amazin.babymonitor.shared.BabyMonitorSettings
import horse.amazin.babymonitor.shared.MESSAGE_PATH_RECEIVER_SET_ENABLED

class SenderWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != MESSAGE_PATH_RECEIVER_SET_ENABLED) {
            return
        }

        val parcel = Parcel.obtain()
        val settings = try {
            parcel.unmarshall(messageEvent.data, 0, messageEvent.data.size)
            parcel.setDataPosition(0)
            parcel.readBundle(classLoader)
        } finally {
            parcel.recycle()
        }

        if (settings == null) {
            return
        }

        when (settings.getString(BabyMonitorSettings.KEY_ACTION)) {
            BabyMonitorSettings.ACTION_START -> {
                val thresholdDb = settings.getFloat(BabyMonitorSettings.KEY_THRESHOLD_DB)
                val durationMs = settings.getInt(BabyMonitorSettings.KEY_MIN_DURATION_MS)
                val nodeId = messageEvent.sourceNodeId
                BabyMonitorSenderService.start(applicationContext, nodeId, thresholdDb, durationMs)
            }
            BabyMonitorSettings.ACTION_STOP -> {
                BabyMonitorSenderService.stop(applicationContext)
            }
        }
    }
}