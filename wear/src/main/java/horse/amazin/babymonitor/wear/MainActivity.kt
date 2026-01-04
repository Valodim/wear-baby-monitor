package horse.amazin.babymonitor.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.LoudnessData
import kotlin.random.Random
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var dataClient: DataClient
    private var lastSent by mutableStateOf<LoudnessReading?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataClient = Wearable.getDataClient(this)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val reading = lastSent
                    val statusText = if (reading == null) {
                        "Baby Monitor (Wear)\nWaiting to send loudness..."
                    } else {
                        "Baby Monitor (Wear)\nLast sent: ${"%.1f".format(reading.db)} dB\n${reading.formattedTime}"
                    }
                    Text(text = statusText)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sendLoudnessSample()
    }

    private fun sendLoudnessSample() {
        val db = Random.nextDouble(30.0, 80.0).toFloat()
        val timestamp = System.currentTimeMillis()
        val request = PutDataMapRequest.create(LoudnessData.PATH).apply {
            dataMap.putFloat(LoudnessData.KEY_DB, db)
            dataMap.putLong(LoudnessData.KEY_TIMESTAMP, timestamp)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
        lastSent = LoudnessReading(db, formatTimestamp(timestamp))
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
