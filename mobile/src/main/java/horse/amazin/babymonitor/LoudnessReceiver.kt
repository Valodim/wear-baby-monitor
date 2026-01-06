package horse.amazin.babymonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.LoudnessData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoudnessReceiver(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _lastReceived = MutableStateFlow<Float?>(null)
    val lastReceived: StateFlow<Float?> = _lastReceived.asStateFlow()

    private val loudnessListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == LoudnessData.PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val db = dataMap.getFloat(LoudnessData.KEY_DB)
                updateLastReceived(db)
            }
        }
    }

    private fun updateLastReceived(value: Float) {
        mainHandler.post {
            _lastReceived.value = value
        }
    }

    fun init() {
        dataClient.addListener(loudnessListener)
    }

    fun close() {
        dataClient.removeListener(loudnessListener)
    }
}