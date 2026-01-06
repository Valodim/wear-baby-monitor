package horse.amazin.babymonitor.wear

import horse.amazin.babymonitor.shared.AutoStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioMonitorServiceState {
    private val _streamStatus = MutableStateFlow("Idle")
    val streamStatus: StateFlow<String> = _streamStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentLoudness = MutableStateFlow<Float?>(null)
    val currentLoudness: StateFlow<Float?> = _currentLoudness.asStateFlow()

    private val _autoStreamConfig =
        MutableStateFlow<horse.amazin.babymonitor.shared.AutoStreamConfig?>(AutoStreamConfig(-75f, 2000))
    val autoStreamConfig: StateFlow<horse.amazin.babymonitor.shared.AutoStreamConfig?> =
        _autoStreamConfig.asStateFlow()

    fun updateStreamStatus(status: String) {
        _streamStatus.value = status
    }

    fun updateStreaming(streaming: Boolean) {
        _isStreaming.value = streaming
    }

    fun updateLoudness(loudness: Float?) {
        _currentLoudness.value = loudness
    }

    fun updateAutoStreamConfig(config: horse.amazin.babymonitor.shared.AutoStreamConfig?) {
        _autoStreamConfig.value = config
    }

}
