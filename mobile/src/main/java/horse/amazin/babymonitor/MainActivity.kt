package horse.amazin.babymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.LoudnessData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var dataClient: DataClient
    private var lastReceived by mutableStateOf<LoudnessReading?>(null)

    private val loudnessListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == LoudnessData.PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val db = dataMap.getFloat(LoudnessData.KEY_DB)
                val timestamp = dataMap.getLong(LoudnessData.KEY_TIMESTAMP)
                lastReceived = LoudnessReading(db, formatTimestamp(timestamp))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataClient = Wearable.getDataClient(this)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val reading = lastReceived
                    val statusText = if (reading == null) {
                        "Baby Monitor (Phone)\nWaiting for loudness..."
                    } else {
                        "Baby Monitor (Phone)\nLoudness: ${"%.1f".format(reading.db)} dB\n${reading.formattedTime}"
                    }
                    Text(text = statusText)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dataClient.addListener(loudnessListener)
    }

    override fun onStop() {
        dataClient.removeListener(loudnessListener)
        super.onStop()
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    private data class LoudnessReading(
        val db: Float,
        val formattedTime: String
    )
}
