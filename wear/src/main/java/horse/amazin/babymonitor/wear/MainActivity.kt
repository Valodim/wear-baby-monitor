package horse.amazin.babymonitor.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var audioStreamController: AudioStreamController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioStreamController = AudioStreamController(applicationContext)
        audioStreamController.start()
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
                val currentLoudness by audioStreamController.currentLoudness.collectAsState()
                val streamStatus by audioStreamController.streamStatus.collectAsState()
                val isStreaming by audioStreamController.isStreaming.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMicPermission = granted
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Baby Monitor (Wear)")
                        Text(
                            text = if (!hasMicPermission) {
                                "Mic permission required"
                            } else {
                                "Current loudness: ${currentLoudness?.let { "%.1f".format(it) } ?: "--"} dB"
                            }
                        )
                        Text(text = "Stream: $streamStatus")
                        if (!hasMicPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Text(text = "Grant microphone")
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                if (isStreaming) {
                                    audioStreamController.stopStreaming()
                                } else {
                                    audioStreamController.startStreaming()
                                }
                            }) {
                                Text(text = if (isStreaming) "Stop streaming" else "Start streaming")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        audioStreamController.resetLoudness()
    }

    override fun onStop() {
        super.onStop()
        audioStreamController.stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioStreamController.stop()
    }
}
