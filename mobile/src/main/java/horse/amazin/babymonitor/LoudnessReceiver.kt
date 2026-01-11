package horse.amazin.babymonitor

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import horse.amazin.babymonitor.shared.MESSAGE_PATH_SENDER_LOUDNESS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class LoudnessReceiver(context: Context) {
    private val messageClient: MessageClient = Wearable.getMessageClient(context)

    private val _lastReceived = MutableStateFlow<Float?>(null)
    val lastReceived: StateFlow<Float?> = _lastReceived.asStateFlow()

    private val messageListener = MessageClient.OnMessageReceivedListener { message ->
        if (message.path == MESSAGE_PATH_SENDER_LOUDNESS) {
            _lastReceived.value = ByteBuffer.wrap(message.data).getFloat()
        }
    }

    fun init() {
        messageClient.addListener(messageListener)
    }

    fun close() {
        messageClient.removeListener(messageListener)
    }
}