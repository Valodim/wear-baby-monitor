package horse.amazin.babymonitor

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AutoStreamConfigData

class AutoStreamConfigSender(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)

    fun updateConfig(
        thresholdDb: Float,
        minDurationMs: Int,
        enabled: Boolean
    ) {
        val request = PutDataMapRequest.create(AutoStreamConfigData.PATH).apply {
            dataMap.putFloat(AutoStreamConfigData.KEY_THRESHOLD_DB, thresholdDb)
            dataMap.putInt(AutoStreamConfigData.KEY_MIN_DURATION_MS, minDurationMs)
            dataMap.putBoolean(AutoStreamConfigData.KEY_ENABLED, enabled)
        }.asPutDataRequest()

        dataClient.putDataItem(request)
            .addOnFailureListener { error ->
                Log.e("AutoStreamConfigSender", "Failed to send config", error)
            }
    }
}
