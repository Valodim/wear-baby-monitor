package horse.amazin.babymonitor.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import horse.amazin.babymonitor.shared.AudioCodecConfig
import horse.amazin.babymonitor.shared.OpusEncoderWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class AudioStreamController(
    val context: Context,
    val onLoudnessSample: (db: Float) -> Unit,
) {
    private var streamSender: StreamSender? = null

    private val _currentLoudness = MutableStateFlow<Float?>(null)
    val currentLoudness: StateFlow<Float?> = _currentLoudness.asStateFlow()

    private var audioThread: Thread? = null

    private var isClosed = false

    fun initialize() {
        if (audioThread != null) {
            throw IllegalStateException("AudioStreamController already initialized!")
        }
        if (isClosed) {
            throw IllegalStateException("AudioStreamController is already closed!")
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("AudioStreamController requires audio permission to be available!")
        }

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
            isClosed = true
            return
        }
        recorder.startRecording()
        audioThread = Thread {
            val pcmBuffer = ShortArray(AudioCodecConfig.FRAME_SIZE_SAMPLES)
            try {
                while (!isClosed) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read == 0) {
                        continue
                    }
                    if (read < pcmBuffer.size) {
                        pcmBuffer.fill(0, read, pcmBuffer.size)
                    }
                    val now = System.currentTimeMillis()

                    val loudnessDb = calculateLoudnessDb(pcmBuffer, read)
                    _currentLoudness.value = loudnessDb

                    if (now - lastSentAt >= 1000L) {
                        onLoudnessSample(loudnessDb)
                        lastSentAt = now
                    }

                    streamSender?.send(pcmBuffer)
                }
            } catch (error: IOException) {
                Log.e("AudioStreamController", "Stream error", error)
            } finally {
                isClosed = true
                streamSender?.close()
                try {
                    recorder.stop()
                } catch (_: IllegalStateException) {
                    // Ignore stop errors when recorder is not active.
                }
                recorder.release()
            }
        }.also { it.start() }
    }

    fun close() {
        isClosed = true
        audioThread?.interrupt()
        audioThread = null

    }

    fun startStreaming(outputStream: OutputStream) {
        stopStreaming()
        this.streamSender = StreamSender(outputStream)
    }

    fun stopStreaming() {
        val channelSender = streamSender ?: return
        this.streamSender = null
        channelSender.close()
    }
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

private class StreamSender(
    private val stream: OutputStream,
) {
    private val encoder = OpusEncoderWrapper()

    val header = ByteArray(4)
    var totalBytesSent = 0L
    var totalFramesSent = 0L
    var lastStatsAt = System.currentTimeMillis()

    var isClosed = false

    fun send(pcmBuffer: ShortArray) {
        if (isClosed) {
            return
        }

        val encoded = encoder.encode(pcmBuffer)
        writeFrame(encoded)
        totalBytesSent += encoded.size + header.size
        totalFramesSent += 1

        val now = System.currentTimeMillis()
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

    private fun writeFrame(payload: ByteArray) {
        val length = payload.size
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        try {
            stream.write(header)
            stream.write(payload)
            stream.flush()
        } catch (e: IOException) {
            Log.d("StreamSender", "Closing stream due to error", e)
            close()
        }
    }

    fun close() {
        isClosed = true
        try {
            stream.close()
        } catch (_: IOException) {
            // Ignore close errors.
        }
        encoder.close()
    }
}