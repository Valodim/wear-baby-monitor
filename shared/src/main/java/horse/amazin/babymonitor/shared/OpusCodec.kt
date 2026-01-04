package horse.amazin.babymonitor.shared

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder

/**
 * Opus codec wrapper backed by Concentus (BSD-3-Clause), a pure Java Opus port.
 * This avoids JNI on Wear OS and keeps CPU usage modest for 16 kHz mono speech frames.
 */
class OpusEncoderWrapper {
    private val encoder = OpusEncoder(
        AudioCodecConfig.SAMPLE_RATE,
        AudioCodecConfig.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP
    ).apply {
        bitrate = AudioCodecConfig.BITRATE_BPS
    }

    fun encode(pcm: ShortArray): ByteArray {
        val output = ByteArray(AudioCodecConfig.MAX_PACKET_SIZE)
        val encodedSize = encoder.encode(
            pcm,
            0,
            AudioCodecConfig.FRAME_SIZE_SAMPLES,
            output,
            0,
            output.size
        )
        require(encodedSize > 0) { "Opus encoding failed with code $encodedSize" }
        return output.copyOf(encodedSize)
    }
}

class OpusDecoderWrapper {
    private val decoder = OpusDecoder(
        AudioCodecConfig.SAMPLE_RATE,
        AudioCodecConfig.CHANNELS
    )

    fun decode(encoded: ByteArray, output: ShortArray): Int {
        val decodedSamples = decoder.decode(
            encoded,
            0,
            encoded.size,
            output,
            0,
            output.size,
            false
        )
        require(decodedSamples > 0) { "Opus decoding failed with code $decodedSamples" }
        return decodedSamples
    }
}
