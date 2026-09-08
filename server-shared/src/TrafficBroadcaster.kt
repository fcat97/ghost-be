package ghostbe.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

@Serializable
data class TrafficEvent(
    val method: String,
    val url: String,
    val ruleMatched: String?,
    val status: Int?
)

object TrafficBroadcaster {
    private val _events = MutableSharedFlow<TrafficEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TrafficEvent> = _events

    fun publish(event: TrafficEvent) {
        _events.tryEmit(event)
    }
}
