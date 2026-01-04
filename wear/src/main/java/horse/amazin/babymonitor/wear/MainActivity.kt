package horse.amazin.babymonitor.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                val context = LocalContext.current
                var hasMicPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMicPermission = granted
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val statusText = if (!hasMicPermission) {
                        "Baby Monitor (Wear)\nMicrophone permission required."
                    } else {
                        val reading = lastSent
                        if (reading == null) {
                            "Baby Monitor (Wear)\nWaiting to send loudness..."
                        } else {
                            "Baby Monitor (Wear)\nLast sent: ${"%.1f".format(reading.db)} dB\n${reading.formattedTime}"
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = statusText)
                        if (!hasMicPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Text(text = "Grant microphone")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasMicPermission()) {
            sendLoudnessSample()
        }
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

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private data class LoudnessReading(
        val db: Float,
        val formattedTime: String
    )
}
