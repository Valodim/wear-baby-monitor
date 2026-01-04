package horse.amazin.babymonitor

import android.os.Bundle
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AudioStreamChannel
import horse.amazin.babymonitor.shared.LoudnessData
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var dataClient: DataClient
    private lateinit var channelClient: ChannelClient
    private lateinit var nodeClient: NodeClient
    private var lastReceived by mutableStateOf<LoudnessReading?>(null)
    private var isPlaying by mutableStateOf(false)
    private var playbackStatus by mutableStateOf("Idle")
    private var currentChannel: ChannelClient.Channel? = null
    private var inputStream: InputStream? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

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

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            if (channel == currentChannel) {
                updatePlaybackStatus("Channel closed")
                stopAudioPlaybackInternal()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataClient = Wearable.getDataClient(this)
        channelClient = Wearable.getChannelClient(this)
        nodeClient = Wearable.getNodeClient(this)
        channelClient.registerChannelCallback(channelCallback)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val reading = lastReceived
                    val loudnessText = if (reading == null) {
                        "Waiting for loudness..."
                    } else {
                        "Loudness: ${"%.1f".format(reading.db)} dB\n${reading.formattedTime}"
                    }
                    val statusText = "Baby Monitor (Phone)\nAudio: $playbackStatus\n$loudnessText"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = statusText)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            if (isPlaying) {
                                stopAudioPlayback()
                            } else {
                                startAudioPlayback()
                            }
                        }) {
                            Text(text = if (isPlaying) "Stop audio" else "Start audio")
                        }
                    }
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
        stopAudioPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        channelClient.unregisterChannelCallback(channelCallback)
        super.onDestroy()
    }

    private fun startAudioPlayback() {
        if (isPlaying) {
            return
        }
        isPlaying = true
        updatePlaybackStatus("Connecting...")
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    updatePlaybackStatus("No watch connected")
                    isPlaying = false
                    return@addOnSuccessListener
                }
                channelClient.openChannel(node.id, AudioStreamChannel.PATH)
                    .addOnSuccessListener { channel ->
                        currentChannel = channel
                        channelClient.getInputStream(channel)
                            .addOnSuccessListener { stream ->
                                inputStream = stream
                                startPlayback(stream)
                            }
                            .addOnFailureListener { error ->
                                updatePlaybackStatus("Input stream error: ${error.message}")
                                stopAudioPlaybackInternal()
                            }
                    }
                    .addOnFailureListener { error ->
                        updatePlaybackStatus("Channel open failed: ${error.message}")
                        isPlaying = false
                    }
            }
            .addOnFailureListener { error ->
                updatePlaybackStatus("Node lookup failed: ${error.message}")
                isPlaying = false
            }
    }

    private fun startPlayback(stream: InputStream) {
        val sampleRate = AudioStreamChannel.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBuffer, 2048)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        updatePlaybackStatus("Playing audio")
        playbackThread = Thread {
            val buffer = ByteArray(bufferSize)
            try {
                while (isPlaying) {
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    if (read > 0) {
                        track.write(buffer, 0, read)
                    }
                }
            } catch (error: IOException) {
                updatePlaybackStatus("Playback error: ${error.message}")
            } finally {
                runOnUiThread {
                    stopAudioPlaybackInternal()
                }
            }
        }.also { it.start() }
    }

    private fun stopAudioPlayback() {
        updatePlaybackStatus(if (isPlaying) "Stopping..." else "Idle")
        stopAudioPlaybackInternal()
        updatePlaybackStatus("Idle")
    }

    private fun stopAudioPlaybackInternal() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // Ignore stop errors when track is not active.
            }
            release()
        }
        audioTrack = null
        inputStream?.run {
            try {
                close()
            } catch (_: IOException) {
                // Ignore close errors.
            }
        }
        inputStream = null
        val channel = currentChannel
        currentChannel = null
        if (channel != null) {
            channelClient.close(channel)
        }
    }

    private fun updatePlaybackStatus(message: String) {
        runOnUiThread {
            playbackStatus = message
        }
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
