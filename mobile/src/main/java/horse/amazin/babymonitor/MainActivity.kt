package horse.amazin.babymonitor

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
class MainActivity : ComponentActivity() {
    private lateinit var autoStreamConfigSender: AutoStreamConfigSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStreamConfigSender = AutoStreamConfigSender(this)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastReceived by BabyMonitorService.lastReceived.collectAsState(initial = null)
                    val playbackStatus by BabyMonitorService.playbackStatus.collectAsState(initial = "Idle")
                    val isServiceActive by BabyMonitorService.isActive.collectAsState(false)
                    var thresholdText by rememberSaveable { mutableStateOf("-75.0") }
                    var durationText by rememberSaveable { mutableStateOf("1000") }
                    var autoStreamEnabled by rememberSaveable { mutableStateOf(true) }

                    val thresholdValue = thresholdText.toFloatOrNull()
                    val durationValue = durationText.toIntOrNull()

                    LaunchedEffect(thresholdValue, durationValue, autoStreamEnabled) {
                        if (thresholdValue != null && durationValue != null) {
                            autoStreamConfigSender.updateConfig(
                                thresholdDb = thresholdValue,
                                minDurationMs = durationValue,
                                enabled = autoStreamEnabled
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Baby Monitor (Phone)")
                        Text(
                            text = if (lastReceived == null) {
                                "Received loudness: -- dB"
                            } else {
                                "Received loudness: ${"%.1f".format(lastReceived)} dB"
                            }
                        )
                        Text(text = "Stream: $playbackStatus")
                        Text(text = "Service: ${if (isServiceActive) "Running" else "Stopped"}")
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(0.85f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { startPlaybackService() },
                                enabled = !isServiceActive,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Start Service")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { stopPlaybackService() },
                                enabled = isServiceActive,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop Service")
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(text = "Auto-Stream Settings")
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = thresholdText,
                            onValueChange = { thresholdText = it },
                            label = { Text("Threshold (dB)") },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { durationText = it },
                            label = { Text("Min duration (ms)") },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enabled",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoStreamEnabled,
                                onCheckedChange = { autoStreamEnabled = it }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startPlaybackService()
    }

    private fun startPlaybackService() {
        val intent = Intent(this, BabyMonitorService::class.java)
        startService(intent)
    }

    private fun stopPlaybackService() {
        val intent = Intent(this, BabyMonitorService::class.java)
        stopService(intent)
    }
}
