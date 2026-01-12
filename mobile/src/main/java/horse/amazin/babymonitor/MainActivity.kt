package horse.amazin.babymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.CAPABILITY_BABY_MONITOR_SENDER
import horse.amazin.babymonitor.shared.WearMessageInteractor
import kotlinx.coroutines.FlowPreview

@OptIn(FlowPreview::class)
class MainActivity : ComponentActivity() {
    private val capabilityClient by lazy { Wearable.getCapabilityClient(applicationContext) }
    private val senderNodes = mutableStateOf<Set<Node>>(emptySet())
    private val wearMessageInteractor = WearMessageInteractor(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val lastReceived by BabyMonitorReceiverService.lastReceived.collectAsState(initial = null)
                    val playbackStatus by BabyMonitorReceiverService.playbackStatus.collectAsState(initial = "Idle")
                    val activeNode by BabyMonitorReceiverService.activeNode.collectAsState(null)

                    var thresholdText by rememberSaveable { mutableStateOf("-75") }
                    var durationText by rememberSaveable { mutableStateOf("1000") }

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
                        Spacer(modifier = Modifier.height(12.dp))

                        if (activeNode != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.85f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { sendStopMessage(activeNode!!) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Stop: ${activeNode!!.displayName}")
                                }
                            }
                        } else {
                            for (node in senderNodes.value) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            sendStartMessage(
                                                node,
                                                thresholdText.toFloat(),
                                                durationText.toInt(),
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Start: ${node.displayName}")
                                    }
                                }
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
                    }
                }
            }
        }
    }

    private fun sendStartMessage(node: Node, thresholdDb: Float, durationMs: Int) {
        BabyMonitorReceiverService.start(applicationContext)
        wearMessageInteractor.sendStartMessage(node, thresholdDb, durationMs)
    }

    private fun sendStopMessage(node: Node) {
        BabyMonitorReceiverService.activeNode.value = null
        wearMessageInteractor.sendStopMessage(node)
    }

    override fun onStart() {
        super.onStart()
        capabilityClient.addListener(capabilityChangedListener, CAPABILITY_BABY_MONITOR_SENDER)
        updateSenderNodes()
    }

    override fun onStop() {
        super.onStop()
        capabilityClient.removeListener(capabilityChangedListener, CAPABILITY_BABY_MONITOR_SENDER)
    }

    val capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener {
        updateSenderNodes()
    }

    private fun updateSenderNodes() {
        capabilityClient.getCapability(
            CAPABILITY_BABY_MONITOR_SENDER,
            CapabilityClient.FILTER_ALL
        ).addOnSuccessListener { capabilityInfo ->
            senderNodes.value = capabilityInfo.nodes
        }
    }
}
