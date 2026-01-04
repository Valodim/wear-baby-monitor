package horse.amazin.babymonitor.shared

import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus

class OpusEncoderWrapper {
    private val frameSize = Constants.FrameSize.fromValue(
        AudioCodecConfig.FRAME_SIZE_SAMPLES
    )
    private val opus = Opus().apply {
        encoderInit(
            Constants.SampleRate._16000(),
            Constants.Channels.mono(),
            Constants.Application.voip()
        )
        encoderSetBitrate(Constants.Bitrate.instance(AudioCodecConfig.BITRATE_BPS))
    }

    fun encode(pcm: ShortArray): ByteArray {
        val encoded = opus.encode(pcm, frameSize)
        return opus.convert(encoded!!)!!
    }
}

class OpusDecoderWrapper {
    private val frameSize = Constants.FrameSize.fromValue(
        AudioCodecConfig.FRAME_SIZE_SAMPLES
    )
    private val opus = Opus().apply {
        decoderInit(
            Constants.SampleRate._16000(),
            Constants.Channels.mono()
        )
    }

    fun decode(encoded: ByteArray, output: ShortArray): Int {
        val decodedBytes = opus.decode(encoded, frameSize)
        val decodedSamples = opus.convert(decodedBytes!!)
        val samplesToCopy = decodedSamples!!.size.coerceAtMost(output.size)
        decodedSamples.copyInto(output, endIndex = samplesToCopy)
        return samplesToCopy
    }
}
