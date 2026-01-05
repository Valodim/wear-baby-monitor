package horse.amazin.babymonitor

import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var playbackController: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackController = PlaybackController(this)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastReceived by playbackController.lastReceived
                    val isPlaying by playbackController.isPlaying
                    val playbackStatus by playbackController.playbackStatus
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
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playbackController.onStart()
    }

    override fun onStop() {
        super.onStop()
        playbackController.onStop()
    }
}
