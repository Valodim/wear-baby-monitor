package horse.amazin.babymonitor.shared

import android.content.Context
import android.os.Bundle
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

class WearMessageInteractor(
    context: Context,
) {
    private val messageClient = Wearable.getMessageClient(context)

    fun sendStartMessage(node: Node, thresholdDb: Float, durationMs: Int) {
        val settings = Bundle().apply {
            putString(BabyMonitorSettings.KEY_ACTION, BabyMonitorSettings.ACTION_START)
            putFloat(BabyMonitorSettings.KEY_THRESHOLD_DB, thresholdDb)
            putInt(BabyMonitorSettings.KEY_MIN_DURATION_MS, durationMs)
        }

        val messageData = marshallBundle(settings)
        messageClient.sendMessage(node.id, MESSAGE_PATH_RECEIVER_SET_ENABLED, messageData)
    }

    fun sendStopMessage(node: Node) {
        val settings = Bundle().apply {
            putString(BabyMonitorSettings.KEY_ACTION, BabyMonitorSettings.ACTION_STOP)
        }

        val messageData = marshallBundle(settings)
        messageClient.sendMessage(node.id, MESSAGE_PATH_RECEIVER_SET_ENABLED, messageData)
    }

    fun sendLoudnessMessage(nodeId: String, db: Float) {
        val messageData = ByteBuffer.allocate(4).apply {
            putFloat(db)
        }.array()

        messageClient.sendMessage(nodeId, MESSAGE_PATH_SENDER_LOUDNESS, messageData)
    }
}