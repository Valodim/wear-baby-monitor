package horse.amazin.babymonitor.wear

import horse.amazin.babymonitor.shared.BabyMonitorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BabyMonitorConfigState {
    private val _babyMonitorConfig =
        MutableStateFlow<BabyMonitorConfig?>(BabyMonitorConfig(-75f, 2000))
    val babyMonitorConfig: StateFlow<BabyMonitorConfig?> =
        _babyMonitorConfig.asStateFlow()
    fun updateAutoStreamConfig(config: BabyMonitorConfig?) {
        _babyMonitorConfig.value = config
    }
}
