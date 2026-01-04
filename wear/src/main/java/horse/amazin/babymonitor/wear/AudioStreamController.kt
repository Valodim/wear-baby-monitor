package horse.amazin.babymonitor.wear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AudioStreamChannel
import horse.amazin.babymonitor.shared.AudioCodecConfig
import horse.amazin.babymonitor.shared.LoudnessData
import horse.amazin.babymonitor.shared.OpusEncoderWrapper
import java.io.IOException
import java.io.OutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioStreamController(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val channelClient: ChannelClient = Wearable.getChannelClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val _streamStatus = MutableStateFlow("Idle")
    val streamStatus: StateFlow<String> = _streamStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentLoudness = MutableStateFlow<Float?>(null)
    val currentLoudness: StateFlow<Float?> = _currentLoudness.asStateFlow()

    private var currentChannel: ChannelClient.Channel? = null
    private var outputStream: OutputStream? = null
    private var audioRecord: AudioRecord? = null
    private var streamThread: Thread? = null

    @Volatile
    private var streaming = false

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            if (channel == currentChannel) {
                updateStreamStatus("Channel closed")
                stopStreamingInternal()
            }
        }
    }

    fun start() {
        channelClient.registerChannelCallback(channelCallback)
    }

    fun stop() {
        channelClient.unregisterChannelCallback(channelCallback)
        stopStreamingInternal()
    }

    fun resetLoudness() {
        _currentLoudness.value = null
    }

    fun startStreaming() {
        if (streaming) {
            return
        }
        streaming = true
        _isStreaming.value = true
        updateStreamStatus("Connecting...")
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    updateStreamStatus("No phone connected")
                    stopStreamingInternal()
                    return@addOnSuccessListener
                }
                channelClient.openChannel(node.id, AudioStreamChannel.PATH)
                    .addOnSuccessListener { channel ->
                        currentChannel = channel
                        channelClient.getOutputStream(channel)
                            .addOnSuccessListener { stream ->
                                outputStream = stream
                                startRecording(stream)
                            }
                            .addOnFailureListener { error ->
                                updateStreamStatus("Output stream error: ${error.message}")
                                stopStreamingInternal()
                            }
                    }
                    .addOnFailureListener { error ->
                        updateStreamStatus("Channel open failed: ${error.message}")
                        stopStreamingInternal()
                    }
            }
            .addOnFailureListener { error ->
                updateStreamStatus("Node lookup failed: ${error.message}")
                stopStreamingInternal()
            }
    }

    fun stopStreaming() {
        updateStreamStatus(if (streaming) "Stopping..." else "Idle")
        stopStreamingInternal()
        updateStreamStatus("Idle")
    }

    private fun startRecording(stream: OutputStream) {
        val sampleRate = AudioCodecConfig.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBuffer, AudioCodecConfig.FRAME_SIZE_SAMPLES * 2)
        var lastSentAt = 0L
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            updateStreamStatus("AudioRecord init failed")
            stopStreamingInternal()
            return
        }
        audioRecord = recorder
        recorder.startRecording()
        updateStreamStatus("Streaming audio")
        streamThread = Thread {
            val encoder = OpusEncoderWrapper()
            val pcmBuffer = ShortArray(AudioCodecConfig.FRAME_SIZE_SAMPLES)
            val header = ByteArray(4)
            var totalBytesSent = 0L
            var totalFramesSent = 0L
            var lastStatsAt = System.currentTimeMillis()
            try {
                while (streaming) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        if (read < pcmBuffer.size) {
                            pcmBuffer.fill(0, read, pcmBuffer.size)
                        }
                        val loudnessDb = calculateLoudnessDb(pcmBuffer, read)
                        val now = System.currentTimeMillis()
                        _currentLoudness.value = loudnessDb
                        if (now - lastSentAt >= 500L) {
                            sendLoudnessSample(loudnessDb, now)
                            lastSentAt = now
                        }
                        val encoded = encoder.encode(pcmBuffer)
                        writeFrame(stream, header, encoded)
                        totalBytesSent += encoded.size + header.size
                        totalFramesSent += 1
                        stream.flush()
                        if (now - lastStatsAt >= 5000L) {
                            val elapsedSeconds = (now - lastStatsAt) / 1000.0
                            val bitrate = (totalBytesSent * 8) / elapsedSeconds
                            Log.i(
                                "AudioStreamController",
                                "Sent $totalFramesSent frames, ${totalBytesSent} bytes " +
                                    "(${bitrate.toInt()} bps)"
                            )
                            totalBytesSent = 0
                            totalFramesSent = 0
                            lastStatsAt = now
                        }
                    }
                }
            } catch (error: IOException) {
                updateStreamStatus("Stream error: ${error.message}")
            } finally {
                stopStreamingInternal()
            }
        }.also { it.start() }
    }

    private fun stopStreamingInternal() {
        streaming = false
        _isStreaming.value = false
        streamThread?.interrupt()
        streamThread = null
        _currentLoudness.value = null
        audioRecord?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // Ignore stop errors when recorder is not active.
            }
            release()
        }
        audioRecord = null
        outputStream?.run {
            try {
                close()
            } catch (_: IOException) {
                // Ignore close errors.
            }
        }
        outputStream = null
        val channel = currentChannel
        currentChannel = null
        if (channel != null) {
            channelClient.close(channel)
        }
    }

    private fun updateStreamStatus(message: String) {
        _streamStatus.value = message
    }

    private fun sendLoudnessSample(db: Float, timestamp: Long) {
        val request = PutDataMapRequest.create(LoudnessData.PATH).apply {
            dataMap.putFloat(LoudnessData.KEY_DB, db)
            dataMap.putLong(LoudnessData.KEY_TIMESTAMP, timestamp)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }

    private fun calculateLoudnessDb(buffer: ShortArray, read: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i < read) {
            val sample = buffer[i]
            val normalized = sample / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
            samples++
            i += 1
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSquares / samples)
        val db = 20.0 * log10(rms.coerceAtLeast(1e-6))
        return db.toFloat()
    }

    private fun writeFrame(stream: OutputStream, header: ByteArray, payload: ByteArray) {
        val length = payload.size
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        stream.write(header)
        stream.write(payload)
    }
}
