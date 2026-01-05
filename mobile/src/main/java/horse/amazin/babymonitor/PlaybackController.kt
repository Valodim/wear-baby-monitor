package horse.amazin.babymonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.AudioStreamChannel
import horse.amazin.babymonitor.shared.AudioCodecConfig
import horse.amazin.babymonitor.shared.LoudnessData
import horse.amazin.babymonitor.shared.OpusDecoderWrapper
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

class PlaybackController(context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val channelClient: ChannelClient = Wearable.getChannelClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _lastReceived = mutableStateOf<Float?>(null)
    val lastReceived: State<Float?> = _lastReceived

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: State<Boolean> = _isPlaying

    private val _playbackStatus = mutableStateOf("Idle")
    val playbackStatus: State<String> = _playbackStatus

    @Volatile
    private var playbackActive = false
    private var currentChannel: ChannelClient.Channel? = null
    private var inputStream: InputStream? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val loudnessListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == LoudnessData.PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val db = dataMap.getFloat(LoudnessData.KEY_DB)
                updateLastReceived(db)
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

    init {
        channelClient.registerChannelCallback(channelCallback)
    }

    fun onStart() {
        dataClient.addListener(loudnessListener)
    }

    fun onStop() {
        dataClient.removeListener(loudnessListener)
        stopAudioPlayback()
    }

    fun onDestroy() {
        channelClient.unregisterChannelCallback(channelCallback)
    }

    fun startAudioPlayback() {
        if (_isPlaying.value) {
            return
        }
        playbackActive = true
        setIsPlaying(true)
        updatePlaybackStatus("Connecting...")
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    updatePlaybackStatus("No watch connected")
                    setIsPlaying(false)
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
                        setIsPlaying(false)
                    }
            }
            .addOnFailureListener { error ->
                updatePlaybackStatus("Node lookup failed: ${error.message}")
                setIsPlaying(false)
            }
    }

    fun stopAudioPlayback() {
        updatePlaybackStatus(if (_isPlaying.value) "Stopping..." else "Idle")
        stopAudioPlaybackInternal()
        updatePlaybackStatus("Idle")
    }

    private fun startPlayback(stream: InputStream) {
        val sampleRate = AudioCodecConfig.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBuffer, AudioCodecConfig.FRAME_SIZE_SAMPLES * 2)
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
            val decoder = OpusDecoderWrapper()
            val header = ByteArray(4)
            val decodedBuffer = ShortArray(AudioCodecConfig.FRAME_SIZE_SAMPLES)
            var totalBytesReceived = 0L
            var totalFramesDecoded = 0L
            var lastStatsAt = System.currentTimeMillis()
            try {
                while (playbackActive) {
                    if (!readFully(stream, header, header.size)) {
                        break
                    }
                    val frameSize = parseFrameLength(header)
                    if (frameSize <= 0 || frameSize > AudioCodecConfig.MAX_PACKET_SIZE) {
                        updatePlaybackStatus("Invalid frame size: $frameSize")
                        break
                    }
                    val encodedFrame = ByteArray(frameSize)
                    if (!readFully(stream, encodedFrame, encodedFrame.size)) {
                        break
                    }
                    val decodedSamples = decoder.decode(encodedFrame, decodedBuffer)
                    track.write(decodedBuffer, 0, decodedSamples)
                    totalBytesReceived += frameSize + header.size
                    totalFramesDecoded += 1
                    val now = System.currentTimeMillis()
                    if (now - lastStatsAt >= 5000L) {
                        val elapsedSeconds = (now - lastStatsAt) / 1000.0
                        val bitrate = (totalBytesReceived * 8) / elapsedSeconds
                        Log.i(
                            "PlaybackController",
                            "Received $totalFramesDecoded frames, $totalBytesReceived bytes " +
                                "(${bitrate.toInt()} bps)"
                        )
                        totalBytesReceived = 0
                        totalFramesDecoded = 0
                        lastStatsAt = now
                    }
                }
            } catch (error: IOException) {
                updatePlaybackStatus("Playback error: ${error.message}")
            } finally {
                stopAudioPlaybackInternal()
            }
        }.also { it.start() }
    }

    private fun stopAudioPlaybackInternal() {
        playbackActive = false
        setIsPlaying(false)
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

    private fun updateLastReceived(value: Float) {
        mainHandler.post {
            _lastReceived.value = value
        }
    }

    private fun setIsPlaying(value: Boolean) {
        mainHandler.post {
            _isPlaying.value = value
        }
    }

    private fun updatePlaybackStatus(message: String) {
        mainHandler.post {
            _playbackStatus.value = message
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = stream.read(buffer, offset, length - offset)
            if (read == -1) {
                return false
            }
            offset += read
        }
        return true
    }

    private fun parseFrameLength(header: ByteArray): Int {
        return ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
    }
}
